# ADR-002: Deterministic gateway simulation

- **Status:** Accepted for the project simulation
- **Decision:** Use the common `GatewayClient` contract with deterministic
  simulated outcomes for the four gateway identifiers.

## Context

The project brief explicitly requests mock adapters and repeatable tests for
successes, timeouts, server errors, declines, rate limits, and delayed replies.
Live gateway accounts, credentials, and a production NPCI/UPI integration
contract are not supplied.

## Consequences

Tests can reproduce failover and reconciliation scenarios without external
services or payment credentials. This does **not** authorize real charges or
constitute live Razorpay, Stripe, PayU, or NPCI integration; provider-specific
adapters, signature formats, secrets, sandbox certification, and operational
verification are still required before any production payment processing.
