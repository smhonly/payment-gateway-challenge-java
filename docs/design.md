# Payment Gateway 

## API Design

### (1) POST /payment

Process a card payment.

### Request

| Component | Field | Type | Required | Validation |
|-----------|-------|------|----------|------------|
| Header | Idempotency-Key | string | Yes | Non-blank |
| Body | card_number | string | Yes | 14-19 characters, numeric only |
| Body | expiry_month | int | Yes | 1-12 |
| Body | expiry_year | int | Yes | Combined with expiry_month, must be in the future |
| Body | currency | string | Yes | 3 characters, one of: USD, GBP, EUR |
| Body | amount | int | Yes | Positive integer, minor currency unit (e.g. 1050 = $10.50) |
| Body | cvv | string | Yes | 3-4 characters, numeric only |

```
POST /payment
Idempotency-Key: 550e8400-e29b-41d4-a716-446655440000
Content-Type: application/json

{
  "card_number": "2222405343248877",
  "expiry_month": 4,
  "expiry_year": 2027,
  "currency": "GBP",
  "amount": 100,
  "cvv": "123"
}
```

### Response

**201 Created**

| Field | Type | Notes |
|-------|------|-------|
| id | UUID | |
| status | string | One of: Pending, Authorized, Declined, Rejected, Failed |
| card_number_last_four | int | |
| expiry_month | int | |
| expiry_year | int | |
| currency | string | |
| amount | int | |

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "status": "Authorized",
  "card_number_last_four": 8877,
  "expiry_month": 4,
  "expiry_year": 2027,
  "currency": "GBP",
  "amount": 100
}
```

**400 Bad Request**

| Scenario | Body |
|----------|------|
| Malformed JSON | `{"message": "Malformed request body"}` |
| Missing Idempotency-Key header | `{"message": "Idempotency-Key header is required"}` |

---

### (2) GET /payment/{id}

Retrieve a previously processed payment.

### Request

| Component | Field | Type | Required |
|-----------|-------|------|----------|
| Path | id | UUID | Yes |

### Response

**200 OK** — Same body schema as POST /payment response.

**404 Not Found**

```json
{"message": "Page not found"}
```

---

## Idempotency

The `Idempotency-Key` header prevents duplicate payment processing on retry.

### Behavior

- First request: SHA-256 hash of the request body is stored with the cached response.
- Replay with the same key: hash is compared. If body differs, the request is rejected (400).    
- If body matches, cached response is returned — bank NOT called again.
- All outcomes are cached, including Rejected.

## Bank Client Resilience

### Rate limit
Prevent PSP server from being attacked by too many requests.

### Circuit breaker
If PSP call failed too many times, circuit breaker will open.
After 10s, circuit breaker will transition to half-open.
If PSP call success, circuit breaker will close.

---

## Pending Payment Handler
If bank client call failed, the payment status = Pending.

The pending payment handler is a scheduler that will check pending status payments every 30s. 
For simple, now the handler just follow the Option 1.(Failed status still is a final status).

### Option 1. For simple, the handler just sets payment status to Failed.   

### Option 2. Retry bankClient.processPayment(Need to store PAN+CVV in DB, they are PCI DSS data)

### Option 3. If bank client supports inquiry API, need query bank status and sync up payment status in paymentsRepository.    
If db status is not fund moved, but bank status is fund moved, need to refund for this long fund case.
(refund is not supported by bank simulator, also out of scope)

---

## Observability — Logs

Four log events are emitted at INFO/WARN level. Each event is one line with `key=value` fields so they can be grep'd and parsed without a log aggregator.

| Event | When | Fields |
|---|---|---|
| `idempotency_hit` | Cache hit on replay (no bank call) | `id idem status elapsed_ms` |
| `payment_outcome` | Every first-time payment reaches a terminal state | `id idem status amount currency elapsed_ms` |
| `bank_response` | Every bank call completes (or fails) | `id idem bank_ms status` where `status ∈ {AUTHORIZED, DECLINED, UNAVAILABLE, ERROR}` |
| `pending_sweep` | Periodic 30 s sweep finds ≥ 1 PENDING payment | `count` |

---

## Metrics

Micrometer + Prometheus registry via `micrometer-registry-prometheus`. Exposed at `GET /actuator/prometheus`.

| Metric | Type | Tags | Description |
|---|---|---|---|
| `payments.idempotency.hits` | Counter | `status` | Idempotent replay count |
| `payments.bank.duration` | Timer | `status` | Bank call latency |
| `payments.processed.total` | Counter | `status`, `currency` | Payment volume by outcome |
| `payments.processing.duration` | Timer | `status`, `currency` | End-to-end latency |
| `payments.pending.sweep.count` | Counter | `status` | Pending sweep runs |
| `payments.pending.sweep.duration` | Timer | — | Sweep duration |

Resilience4j metrics (`resilience4j.circuitbreaker.*`, `resilience4j.ratelimiter.*`) are auto-exported.

---

## Health Checks

| Endpoint | Purpose | Includes |
|---|---|---|
| `GET /actuator/health` | Overall status | `diskSpace`, `ping`, `circuitBreakers`, `bank` |
| `GET /actuator/health/liveness` | k8s probe — JVM alive | `ping` |
| `GET /actuator/health/readiness` | k8s probe — can accept traffic | `readinessState`, `circuitBreakers`, `bank` |

`BankHealthIndicator` reports bank simulator reachability. Circuit breaker health trips readiness when the bank is failing, shedding traffic at the pod level.

---

## Open Questions

If we don't save Pending status resposne in Idempotency DB, we can retry for pending status.     
But there will be 1 risk:    
**Duplicate pay risk on retry.** If the first call times out at the bank (bank may have authorized) and the retry succeeds, the bank receives two authorizations. 
Bank API must support idempotency key(e.g. `merchantRefId` / `idempotencyKey` passed to the bank).
