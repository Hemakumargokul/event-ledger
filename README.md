# Event Ledger

A small event-driven financial system of two Spring Boot microservices: clients submit
credit/debit events to a public **Event Gateway**, which forwards them to an internal
**Account Service** that owns balances and transaction history. The system is idempotent,
tolerates out-of-order events, and degrades gracefully when the Account Service is down.

## Architecture

```
                 POST /events
  Client ──────────────────────────► ┌───────────────────┐
          GET /events/{id}           │   Event Gateway    │      POST /accounts/{id}/transactions
          GET /events?account=...    │   :8080            │ ───────────────────────────────────►
          GET /accounts/{id}/balance │   H2 (events)      │      GET  /accounts/{id}/balance     ┌───────────────────┐
                                     │                    │ ◄─────────────────────────────────── │  Account Service  │
                                     │  Resilience4j:     │      RestClient + traceparent        │  :8081            │
                                     │  circuit breaker   │                                      │  H2 (accounts,    │
                                     │  + timeout + retry │                                      │      transactions)│
                                     └───────────────────┘                                       └───────────────────┘
```

- **Event Gateway** (port 8080): validates and stores every accepted event locally
  (idempotent on `eventId`), applies it downstream, and serves all event reads from its
  own store — so reads keep working when the Account Service is down. Also proxies
  balance queries.
- **Account Service** (port 8081): owns account balances; applies transactions
  idempotently (`transactionId` = upstream `eventId` is the primary key). No shared
  state — the services communicate only over HTTP.

### API summary

| Service | Endpoint | Purpose |
|---|---|---|
| Gateway | `POST /events` | Submit an event: `201` new, `200` duplicate replay, `429` rate limited |
| Gateway | `GET /events/{eventId}` | Fetch a stored event |
| Gateway | `GET /events?account={id}` | Events for an account, chronological by `eventTimestamp` |
| Gateway | `GET /accounts/{id}/balance` | Balance, proxied from the Account Service |
| Account | `POST /accounts/{id}/transactions` | Apply a transaction (internal, called by the Gateway) |
| Account | `GET /accounts/{id}/balance` | Current balance |
| Account | `GET /accounts/{id}` | Balance + last 10 transactions |
| Both | `GET /health`, `GET /actuator/prometheus` | Health with DB diagnostics; Prometheus metrics |

## Prerequisites

- **Docker + Docker Compose** (to run), or **JDK 25 + Maven 3.9+** (to build/run manually)
- `curl` and `jq` for the smoke test

## Running

### Docker Compose (recommended)

```bash
docker compose up --build
```

Gateway on `http://localhost:8080`, Account Service on `http://localhost:8081`,
Jaeger trace UI on `http://localhost:16686`.
If those host ports are taken on your machine, override them:

```bash
GATEWAY_PORT=18080 ACCOUNT_SERVICE_PORT=18081 JAEGER_UI_PORT=26686 docker compose up --build
```

Submit an event:

```bash
curl -X POST http://localhost:8080/events \
  -H 'Content-Type: application/json' \
  -d '{
        "eventId": "evt-1001",
        "accountId": "acct-123",
        "type": "CREDIT",
        "amount": 150.00,
        "currency": "USD",
        "eventTimestamp": "2026-05-15T14:02:11Z",
        "metadata": {"source": "mainframe-batch"}
      }'

curl http://localhost:8080/accounts/acct-123/balance
```

### Smoke test

With the stack up:

```bash
./scripts/smoke-test.sh          # honors GATEWAY_PORT / ACCOUNT_SERVICE_PORT
```

It exercises submit → duplicate replay → out-of-order listing → balance, then stops the
Account Service (`docker compose stop account-service`) and verifies graceful
degradation: writes return `503`, local reads and Gateway health keep working, and the
same event retries successfully after recovery.

### Manual run (no Docker)

```bash
cd account-service && mvn spring-boot:run     # terminal 1
cd event-gateway  && mvn spring-boot:run      # terminal 2
```

## Tests

```bash
mvn test        # from the repo root — runs both modules (76 tests)
```

The suite is a pyramid: fast unit/slice tests for domain logic and API contracts
(`@WebMvcTest`, `@DataJpaTest`, Mockito), component tests with WireMock standing in for
the Account Service (resiliency, wire contract, trace propagation), Pact contract tests
pinning the Gateway↔Account wire contract from both sides, and a two-service
integration test that boots both real contexts on random ports.

| Requirement | Where it's proven |
|---|---|
| Idempotency | `EventServiceTest` (duplicate never calls downstream), `AccountServiceTest` (duplicate `transactionId` replays, balance unchanged, concurrent races resolve to exactly-once) |
| Out-of-order tolerance | `EventRepositoryTest` (chronological listing), `AccountServiceTest` (same balance regardless of arrival order), `EndToEndIntegrationTest` |
| Balance correctness | `AccountServiceTest` (`BigDecimal` math, negative balances allowed by design) |
| Validation | `EventControllerTest` / `AccountControllerTest` (400 with per-field details; nothing reaches the service) |
| Resiliency | `GatewayResilienceTest` (circuit opens on repeated 5xx and fails fast, half-open recovery, exactly-3-attempts retry, 4xx neither retried nor recorded, saturated bulkhead sheds without a downstream call) |
| Trace propagation | `GatewayTracingTest` (downstream `traceparent` carries the caller's trace ID), `EndToEndIntegrationTest` (both services log one shared trace ID) |
| Graceful degradation | `GatewayDegradationTest` (503 writes with nothing persisted, local reads fine, health UP) plus the smoke test against real containers |
| Service contract (Pact) | `AccountServicePactTest` (consumer: the real client generates `pacts/event-gateway-account-service.json`), `GatewayContractVerificationTest` (provider: replays every interaction against the real service with seeded state) |

## Resiliency design

The Gateway→Account call is wrapped in four layered policies (Resilience4j):

- **Circuit breaker** (the primary pattern): count-based 10-call window, opens at 50%
  failures, 10s open-state wait, 3 half-open probes. When the Account Service is
  repeatedly failing, hammering it helps nobody — the breaker fails fast (503 in
  milliseconds instead of stacked timeouts) and gives it room to recover.
- **Timeouts** bound each attempt (connect 1s, read 2s): a hung response is as bad as
  no response for a synchronous API.
- **Retry with exponential backoff + jitter** (3 attempts, 200ms initial, ×2, 50%
  jitter) heals transient blips without a thundering herd. Retrying is *safe* only
  because the Account Service is idempotent — a retry whose first attempt actually
  committed replays instead of double-applying.
- **Bulkhead** (semaphore, max 10 concurrent calls, no wait): caps how many servlet
  threads a hung Account Service can pin at once. When full, excess calls shed
  immediately with `503` "Account Service is overloaded" — local back-pressure, so
  it is neither retried nor counted by the circuit breaker.

Only connection failures, timeouts, and downstream `5xx` trip these policies; a `4xx`
means a Gateway bug and is neither retried nor counted by the breaker.

The Gateway's public API is also **rate limited** (bonus): one global fixed window of
50 requests/s shared by all clients across all `/events*` and balance endpoints,
fail-fast with no queueing. Excess requests get `429 Too Many Requests` — with
`Retry-After` and `X-RateLimit-Limit` / `X-RateLimit-Remaining` / `X-RateLimit-Reset`
headers so clients can back off instead of blind-retrying — before any duplicate check
or downstream call, so a rejected submit persists nothing and is safe to retry.
`/health` and `/actuator/*` stay unlimited so probes and scrapes keep working under
load.

When the Account Service is down: `POST /events` → `503` (nothing persisted, safe to
retry the same `eventId`); all `GET /events*` reads work normally from local data;
balance proxy → `503` with an explicit message; Gateway health stays `UP`.

## Observability

- **Distributed tracing**: one trace ID per client request, propagated over W3C
  `traceparent` and logged by both services (Micrometer Tracing, OTel bridge).
- **Trace visualization**: under Docker Compose, both services export OTLP spans to an
  **OpenTelemetry Collector**, which forwards them to **Jaeger** — open
  `http://localhost:16686`, pick `event-gateway`, and a single `POST /events` shows as
  one trace with spans across both services. Export activates only when the
  `MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT` env var is set (compose sets
  it), so manual runs and tests are unaffected.
- **Structured logs**: ECS JSON on stdout with service name, trace and span IDs.
- **Metrics**: `/actuator/prometheus` on both services, including
  `ledger.events.submitted` (by type/outcome), `ledger.account.call` latency
  (by outcome), and Resilience4j circuit-breaker state.

## Design decisions and trade-offs

- **Duplicate events replay, never error**: clients may resend; both services treat the
  ID as the idempotency key and return the original outcome with `200`.
- **Out-of-order events are accepted as-is**: balances are commutative sums, listings
  sort by `eventTimestamp`. A balance read between an out-of-order debit and its credit
  can show an interim negative value — accepted, since rejecting early-arriving events
  would lose data.
- **Gateway crash window**: if the Gateway dies between the downstream commit and its
  local insert, a client retry heals it (downstream replay + local insert). A durable
  outbox was considered and deferred as out of scope.
- **H2 in-memory** per the assignment: data resets on restart.
- **Single currency per account**: stored and echoed, not converted or enforced.
