# Transaction State Machine Specification

Implementation: `com.payflow.domain.TransactionState` (transition table) and
`com.payflow.service.StateService` (validation + audit).

## States

| State | Meaning | Terminal |
|---|---|---|
| `CREATED` | Transaction row created; idempotency key reserved | no |
| `ROUTING` | Router has ranked candidate gateways | no |
| `AUTH_INITIATED` | Authorisation sent — **outcome unknown** until a response or webhook | no |
| `AUTHORISED` | Funds held at the gateway; capture pending | no |
| `CAPTURE_INITIATED` | Capture request sent | no |
| `CAPTURED` | Funds captured in full | no |
| `PARTIALLY_CAPTURED` | Partial capture; remaining hold tracked in `captured_paise` | no |
| `CAPTURE_FAILED` | Capture rejected/errored; retryable | no |
| `AUTH_FAILED` | Authorisation declined or failed | no |
| `AUTH_EXPIRED` | UPI mandate window elapsed (FS-12) | **yes** |
| `RETRYING` | Failing over to the next ranked gateway | no |
| `VOID_INITIATED` / `VOIDED` | Authorisation hold released (A7.1 #5, FS-05 remainder) | **yes** (VOIDED) |
| `REFUND_INITIATED` / `REFUNDED` | Refund in flight / completed (incl. from `SETTLED`, FS-08) | **yes** (REFUNDED) |
| `SETTLED` | Included in a gateway settlement batch | no |
| `RECONCILIATION_MISMATCH` | Gateway disagrees with internal state (FS-11) | no |
| `FAILED_TERMINAL` | Retries exhausted | **yes** |

## Transition table

```
CREATED ─────────────▶ ROUTING ─────────▶ AUTH_INITIATED
                            │                   │  ▲
                            │                   │  └─ webhook late success
                            │                   ├─▶ AUTHORISED ──▶ CAPTURE_INITIATED
                            │                   ├─▶ AUTH_FAILED ──▶ RETRYING ──▶ AUTH_INITIATED
                            │                   └─▶ AUTH_EXPIRED (terminal)
                            │                            │            │
                            │                            │            ├─▶ CAPTURED ──▶ SETTLED
                            │                            │            │        │
                            │                            │            │        ├─▶ REFUND_INITIATED ──▶ REFUNDED
                            │                            │            │        └─▶ RECONCILIATION_MISMATCH
                            │                            │            └─▶ PARTIALLY_CAPTURED ──▶ VOID_INITIATED
                            │                            │                          └─▶ CAPTURE_INITIATED
                            │                            └─▶ VOID_INITIATED ──▶ VOIDED (terminal)
                            └─▶ FAILED_TERMINAL (terminal)
AUTH_FAILED ──▶ FAILED_TERMINAL (terminal)
CAPTURE_FAILED ──▶ CAPTURE_INITIATED | FAILED_TERMINAL
RETRYING ──▶ FAILED_TERMINAL (terminal)
```

Invariants (enforced in code, asserted in `StateMachineTest`):

1. **No terminal state has outgoing transitions** — `FAILED_TERMINAL`, `REFUNDED`,
   `VOIDED`, `AUTH_EXPIRED`.
2. **`CAPTURED` is reachable only from `CAPTURE_INITIATED`** — a capture can never
   be asserted without a capture request having been sent.
3. **Refund is valid from `CAPTURED` and `SETTLED`** (FS-08) but never from
   `CREATED` (FS-15 corruption attempt → `IllegalTransitionException`).
4. **`AUTH_INITIATED` accepts a webhook-driven `AUTHORISED`** — the "outcome
   unknown" escape hatch used by FS-06/FS-12.

## Concurrency

Per spec A4.2, `StateService` acquires a `PESSIMISTIC_WRITE` lock
(`SELECT ... FOR UPDATE`) **only** for the transition itself
(`findByIdForUpdate` → validate → update → append audit → commit). No database
connection is held during gateway I/O, which is the failure mode described in
case study C5. `@Version` on `transactions` adds optimistic detection for
concurrent writers outside the lock.

## Audit

Every successful transition appends an immutable row to
`transaction_state_log` (actor, from_state, to_state, detail, timestamp).
Rejected transitions change nothing and are not logged as transitions.
