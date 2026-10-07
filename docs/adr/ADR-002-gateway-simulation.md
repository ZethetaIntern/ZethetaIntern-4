# ADR-002: Simulated gateways

Status: accepted

## Context

Real gateways cannot be called (B4.1: no internet), and the test harness drives failures with
request headers (B4.3). The orchestrator must still face realistic behaviour: idempotent charges,
a status API that can disagree with our records, settlement reports, and gateway-specific formats.

## Decision

- One interface, `PaymentGateway` (authorize, capture, refund, void, fetchStatus,
  settlementReport). Four adapters (`RazorpayAdapter`, `StripeAdapter`, `PayUAdapter`, `UpiAdapter`)
  extend `SimulatedGateway` and supply the gateway's reference format (`pay_…`, `pi_…`, 11-digit
  `mihpayid`, 12-digit UPI RRN), idempotency field (`X-Razorpay-Idempotency-Key`,
  `Idempotency-Key`, `txnid`, `txnRef`), decline codes and response shape. UPI has no separate
  capture: an intent payment returns `CAPTURED`, a collect returns `PENDING`.
- `MockGatewayLedger` is the gateways' own books. `authorise` is keyed by the idempotency token, so
  repeating it returns the same charge (no double charge on retries). It also answers status and
  settlement queries; the harness can override them with `POST /api/v1/mock/gateways/{g}/status`
  and `/settlement`.
- B4.3 headers are parsed by `MockControl`: `X-Mock-Response` (`success`, `timeout`,
  `server-error`, `decline`, `rate-limit`, plus `pending`), `X-Mock-Delay-Ms`,
  `X-Mock-Gateway-Down`. Extensions let one request fail one gateway while another succeeds:
  per-target values (`razorpay=timeout, payu.capture=server-error, refund=decline`),
  `X-Mock-Gateway`, `X-Mock-Operation`, `X-Mock-Gateway-Down: razorpay,payu`, `X-Mock-Retry-After`.
- `timeout` really hangs (30 s) and is cut off by the orchestrator's attempt budget. A successful
  call records the charge before applying the delay, modelling "gateway processed it, response is
  slow" (needed for FS-06). Without headers every call succeeds; optional latency and A3.4 failure
  rates via `payflow.mock.simulate-latency` / `random-failures`.
- `POST /api/v1/mock/upi/{id}/callback` simulates the NPCI switch sending an RSA-signed callback
  through the real webhook endpoint; `/api/v1/mock/webhooks/{g}/sign` returns signature headers;
  `/api/v1/mock/reset` clears simulator state between scenarios. The mock controller is disabled
  with `PAYFLOW_MOCK_ENABLED=false`.

## Consequences

- Replacing a mock with a real HTTP client means implementing `PaymentGateway`; the orchestrator is
  unchanged.
- The ledger is in memory: after a restart the simulated gateways "forget" earlier charges, so
  reconciliation reports them as unknown.
