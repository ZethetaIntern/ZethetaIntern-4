# Architecture

## Components

```
┌─────────────┐   X-API-Key    ┌──────────────────────────────────────────┐
│  Merchant   │ ────────────▶ │ ApiKeyFilter → PaymentController          │
│  / Client   │               │   Idempotency-Key → PaymentService.create │
└─────────────┘               └───────────────┬──────────────────────────┘
                                             │ 1. persist (CREATED)
                                             ▼
                                   ┌─────────────────────┐
                                   │  RoutingEngine      │  weights from routing_config
                                   │  rank(method,amt)   │  health + circuit breaker
                                   └──────────┬──────────┘
                                              │ ranked gateways
                                              ▼
   ┌──────────────── PaymentService.process() ────────────────┐
   │ for each ranked gateway:                                  │
   │   StateService.applyTransition (pessimistic lock)        │
   │   gateway.authorize()  ← shared 2000ms failover deadline  │
   │   success → AUTHORISED → captureFlow() → CAPTURED        │
   │   failure → AUTH_FAILED → RETRYING → next gateway        │
   └───────────────────────────────────────────────────────────┘
                    ▲                          ▲
   POST /webhooks   │                          │  @Scheduled
   ┌────────────────┴────────────┐  ┌──────────┴────────────────────┐
   │ WebhookService              │  │ ReconciliationService          │
   │ 1. HMAC verify (const-time) │  │ flags stuck intermediate      │
   │ 2. dedup PK(gw,event_id)    │  │ states → reconciliation_log    │
   │ 3. reconcile onto FSM        │  └────────────────────────────────┘
   └─────────────────────────────┘
```

## State machine (two-phase)

```
CREATED ──▶ ROUTING ──▶ AUTH_INITIATED ──▶ AUTHORISED ──▶ CAPTURE_INITIATED
                        │     │  ▲              │              │   │   │
                        │     │  └── webhook ───┘              │   │   └─▶ CAPTURE_FAILED
                        ▼     ▼                                 ▼   └────▶ PARTIALLY_CAPTURED
                   RETRYING ◀──┘                              CAPTURED ──▶ REFUND_INITIATED ──▶ REFUNDED
                     │  ▲                                                        (terminal)
                     │  └── AUTH_FAILED (failover to next gateway)
                     ▼
              FAILED_TERMINAL (terminal)
```

- `AUTH_INITIATED` means "outcome unknown" — a late webhook (FS-06/FS-12)
  reconciles it to `AUTHORISED` or `AUTH_FAILED`.
- Illegal transitions (e.g. `CREATED → REFUNDED`, FS-15) throw
  `IllegalTransitionException` and leave state untouched.

## Locking strategy (spec A4.2)

1. `PESSIMISTIC_WRITE` lock is taken only inside `StateService` for the duration
   of a single state transition (`findByIdForUpdate` + update + audit insert),
   committed in a `REQUIRES_NEW` transaction.
2. Locks are **never** held during gateway I/O — the gateway call happens after
   the transition transaction commits.
3. `@Version` optimistic locking on `transactions` guards concurrent writers.
4. Webhook dedup relies on the `PK(gateway, event_id)` constraint inside one
   transaction, so concurrent duplicate deliveries cannot double-process.

## Database (PostgreSQL 15+)

Schema: Flyway migration `src/main/resources/db/migration/V1__initial_schema.sql` — `transactions`,
`transaction_state_log` (immutable audit), `gateway_routes` (config + health
metrics), `routing_config` (hot-tunable weights), `gateway_attempts`,
`idempotency_keys` (24h expiry), `processed_webhook_events`,
`reconciliation_log`, `refunds`.

Hibernate `ddl-auto=update` is limited to dev/test (H2 in PostgreSQL
compatibility mode); the PostgreSQL profile applies Flyway migrations and uses
`ddl-auto=validate`.

## Gateway adapters

`GatewayClient` (`authorize` / `capture` / `refund`) is implemented by
`SimulatedGatewayClient`, whose outcomes are deterministic per reference
(SHA-256 seeded) so routing, failover and reconciliation are reproducible.
Live provider adapters and merchant-specific credentials are not included.
