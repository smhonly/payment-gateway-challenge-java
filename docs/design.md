# Payment Gateway — API Design

## POST /payment

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

## GET /payment/{id}

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

- First request with a key: processes normally, bank is called, response is cached.
- Replay with the same key: returns the cached response. Bank is NOT called again. Same `id` returned.
- All outcomes are cached, including Rejected.

Actually bank simulator also need to add an Idempotency Key, such as merchantRefId to avoid duplicated pay.

---

## Bank Client Resilience

### Rate limit
Prevent PSP server to be attached by too much requests.

### Circuit breaker
If PSP call failed too much times, circuit breaker will open.
After 10s, circuit breaker will transition to half-open.
If PSP call success, circuit breaker will close.

---

## Pending Payment Handler
If bank client call failed, the payment status = Pending.

The pending payment handler is a scheduler that will check pending status payments every 30s. 
For simple, now the handler just follow the Option 1.(Failed status still is a final status).

### Option 1. For simple, then handler just sets payment status to Failed.   

### Option 2. Retry bankClient.processPayment(Need to store PAN+CVV in DB, they are PCI DSS data)

### Option 3. If bank client supports inquiry API, need query status and update payment status in paymentsRepository.    
If db status is not fund moved, but bank status is fund moved, need to refund for this long fund case.(refund is out of scope)

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

## Health Checks

`spring-boot-starter-actuator` is wired in. Endpoints exposed:

| Endpoint | Purpose |
|---|---|
| `GET /actuator/health` | Overall status — 200 `UP` / 503 `DOWN` |
| `GET /actuator/health/liveness` | k8s liveness probe — JVM is alive |
| `GET /actuator/health/readiness` | k8s readiness probe — pod can accept traffic |

Liveness checks only that the JVM is responsive. 
Readiness by default includes only `readinessState`; 
The bank circuit breaker and rate limiter are not included. To shed traffic when the bank side is failing, add:

```properties
management.health.circuitbreakers.enabled=true
management.endpoint.health.group.readiness.include=readinessState,circuitBreakers
```

Then `circuitBreakers` indicator reports `UP` / `DOWN` based on `resilience4j.circuitbreaker.instances.bank`。
K8s will stop routing new requests to the pod while the breaker is open.    
