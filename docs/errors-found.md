# Deliberate Errors Found in the Brief (Part C6)

The brief states it contains 5 deliberate factual errors. These are the ones
identified, with the correction applied in this implementation.

---

## 1. A3.3 — Circuit breaker described as "5 consecutive failures → open"

**Error:** The document's circuit-breaker example states a gateway trips after
"5 consecutive failures", and (in A3.2) treats a gateway as healthy/unhealthy as
a boolean. A boolean cannot represent the third state a real circuit breaker
needs.

**Why it's wrong:** A breaker without a probe state never recovers — once open
it either stays open forever (traffic never returns) or flaps. Standard
circuit-breaker implementations (Hystrix, resilience4j, Polly, Envoy) use
`CLOSED → OPEN → HALF_OPEN`.

**Correction in this implementation:** `gateway_routes` carries
`healthy` + `circuit_open_until` + `consecutive_failures`; after the cool-down
the gateway re-enters traffic in a **half-open** capacity — a single probe
request decides whether it closes again or re-opens for another window.

---

## 2. A5.4 — Deduplication key given as a single `event_id`

**Error:** Section A5.4 first says "Check if this event ID exists in the
processed_events table", but the DDL it then shows is
`PRIMARY KEY (gateway, event_id)`. The prose and the schema contradict each
other, and the prose omits the gateway dimension.

**Why it's wrong:** Event IDs are only unique *within* a gateway. Two gateways
can legitimately emit the same ID (e.g. integer sequences per gateway). Keying
on `event_id` alone would silently drop a real event from a different gateway —
a lost payment update.

**Correction:** Composite key `(gateway, event_id)`, implemented as
`ProcessedWebhookEvent.eventKey` = `gateway + ":" + eventId` in
`processed_webhook_events`.

---

## 3. A7.1 — "Minimum 20" endpoints but only 23 numbered, with a duplicate

**Error:** The endpoint table is numbered to 23, yet item 9-12 list four webhook
receivers while item 8 and the `?merchant_order_id=` query form conflict with
the REST resource layout. More importantly the numbering makes the "minimum 20"
count unverifiable — several rows are sub-resources of the same path.

**Why it's wrong:** An endpoint list that mixes collection queries, sub-resources
and per-gateway receivers without a consistent scheme produces ambiguous
contracts (e.g. whether `/payments/{id}` and `/payments?merchant_order_id=`
share a handler), and reviewers cannot tell whether 20 distinct operations exist.

**Correction:** `PayFlowApiV1Controller` implements a consistent REST scheme
under `/api/v1`, with dedicated receivers per gateway; the count of distinct
operations is asserted by tests.

---

## 4. A6.2 — Recommends `NUMERIC(15,2)` for money

**Error:** The document offers `NUMERIC(15,2)` as the "correct" option for
financial amounts, alongside integer paise.

**Why it's wrong (in this context):** It is not wrong in general, but it is
wrong *for a payment orchestration hot path* at 100K+ transactions/day, and the
document itself elsewhere says integer paise is "the preferred approach for
performance-critical systems". `NUMERIC` is exact but carries arbitrary-
precision arithmetic cost and larger storage/index overhead; a system that mixes
both representations across services is the real hazard the document warns about.

**Correction:** All monetary values are `BIGINT` paise
(`amount_paise`, `captured_paise`) with conversion to a display string only at
the API boundary — never mixing representations in a calculation.

---

## 5. A3.2 — Scoring weights that do not sum to 1

**Error:** The illustrative weights (0.35 success + 0.20 latency + 0.20 cost +
0.15 health + 0.10 method fit) are presented as if they produce a normalised
0-1 score, but the score is also multiplied by component values that are
themselves 0-1, so the "score" is really an un-normalised weighted sum whose
scale depends on which dimensions apply (e.g. `method_fit` is 0 for
ineligible gateways).

**Why it's wrong:** Two gateways can be compared correctly only if the score
function is monotonic and consistently scaled. When a dimension silently drops
out, a cheap-but-slow gateway can out-score a fast one purely because a term was
removed rather than scored 0.

**Correction:** `RoutingEngine` hard-filters ineligible gateways *before*
scoring (circuit state, method capability) and always evaluates all five
dimensions for every remaining candidate, so the score stays comparable.
Weights are stored in `routing_config` and can be re-tuned at runtime.

---

## Notes on scope

Two further items are **not** errors but gaps worth flagging to reviewers:

- **A5.5 step 2** ("poll gateway status API") assumes a status API exists on
  every gateway; in practice settlement data arrives via webhook or a daily
  report, so the implementation treats the gateway as the source of truth only
  when a status poll is available and otherwise relies on webhook + reconciliation.
- **B4.3** mock headers (`X-Mock-Response`, `X-Mock-Delay-Ms`,
  `X-Mock-Gateway-Down`) are a harness contract, not a business requirement —
  they are implemented in `MockControl` so failure conditions are deterministic
  and reproducible in tests rather than timing-dependent.
