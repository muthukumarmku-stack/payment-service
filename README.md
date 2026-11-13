# Payment Service

A Payment Management REST API built as a learning project: Java 21, Spring Boot 3.5, Spring Data JPA, PostgreSQL 16, Flyway, JUnit 5, Mockito and Testcontainers.

It creates payments, returns them by id, moves them through a status lifecycle, and keeps an audit trail of every status change.

## Run it

Prerequisites: JDK 21 or newer, and Docker. Maven comes with the project (`mvnw`).

```bash
docker compose up -d          # PostgreSQL 16 on localhost:5432
./mvnw spring-boot:run        # Windows: .\mvnw.cmd spring-boot:run
```

The API listens on **http://localhost:8081** (8080 was taken on the dev machine). Flyway creates the tables on first start.

| What | URL |
|---|---|
| Swagger UI | http://localhost:8081/swagger-ui.html |
| OpenAPI JSON | http://localhost:8081/v3/api-docs |
| Health | http://localhost:8081/actuator/health |

Configuration comes from environment variables, with defaults that match `compose.yaml`:

| Variable | Default |
|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/payments` |
| `DB_USERNAME` / `DB_PASSWORD` | `payments` / `payments` (local only) |
| `SERVER_PORT` | `8081` |

Stop the database with `docker compose down`. Add `-v` to also delete its data.

## API

Base path: `/api/v1/payments`

| Method | Path | Purpose | Success | Errors |
|---|---|---|---|---|
| `POST` | `/` | Create a payment (starts as `PENDING`) | 201 + `Location` | 400, 409, 422 |
| `GET` | `/{paymentId}` | Payment details | 200 | 400, 404 |
| `PATCH` | `/{paymentId}/status` | Change status | 200 | 400, 404, 409 |
| `GET` | `/{paymentId}/history` | Status changes, oldest first | 200 | 400, 404 |
| `GET` | `/?status=&payerId=&from=&to=&page=&size=&sort=` | Filtered, paged payment history | 200 | 400 |

### Status lifecycle

```
PENDING ──► PROCESSING ──► COMPLETED ──► REFUNDED
   │             │
   ▼             ▼
CANCELLED      FAILED
```

`FAILED`, `CANCELLED` and `REFUNDED` are final. Any other move returns **409** with the statuses that would have been allowed.

### Examples

Create a payment. The `Idempotency-Key` header is optional: retrying with the same key and the same body returns the original payment instead of creating a second one.

```bash
curl -i -X POST http://localhost:8081/api/v1/payments \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: order-1001-attempt" \
  -d '{
        "reference": "ORDER-1001",
        "amount": 1500.50,
        "currency": "INR",
        "method": "UPI",
        "payerId": "alice",
        "payeeId": "shop-1",
        "description": "Order 1001"
      }'
```

```json
{
  "id": "9cb0ce21-e527-4626-94d4-0f15f00b2c6e",
  "reference": "ORDER-1001",
  "amount": 1500.5000,
  "currency": "INR",
  "method": "UPI",
  "status": "PENDING",
  "payerId": "alice",
  "payeeId": "shop-1",
  "description": "Order 1001",
  "createdAt": "2026-11-13T17:43:45.120Z",
  "updatedAt": "2026-11-13T17:43:45.120Z"
}
```

Read it, move it along, and see its history:

```bash
curl http://localhost:8081/api/v1/payments/{id}

curl -X PATCH http://localhost:8081/api/v1/payments/{id}/status \
  -H "Content-Type: application/json" \
  -d '{ "status": "PROCESSING", "reason": "Sent to gateway" }'

curl http://localhost:8081/api/v1/payments/{id}/history
```

Search the payment history. All filters are optional. `from` is inclusive, `to` is exclusive, and both are ISO-8601 instants. Results are newest first by default. `size` defaults to 20 and is capped at 100.

```bash
curl "http://localhost:8081/api/v1/payments?payerId=alice&status=COMPLETED&from=2026-11-01T00:00:00Z&page=0&size=20&sort=amount,desc"
```

Sortable fields: `createdAt`, `updatedAt`, `amount`, `status`, `reference`.

### Request rules

| Field | Rule |
|---|---|
| `reference` | required, ≤ 64 chars, unique |
| `amount` | required, > 0, ≤ 15 integer digits and ≤ 4 decimals |
| `currency` | required, ISO 4217 code known to the JDK (`INR`, `USD`, …) |
| `method` | `CARD`, `BANK_TRANSFER`, `UPI` or `WALLET` |
| `payerId`, `payeeId` | required, ≤ 64 chars, must differ |
| `description` | optional, ≤ 255 chars |
| `Idempotency-Key` header | optional, ≤ 100 chars |

### Errors

Every error is [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) `application/problem+json` with a stable `code` that clients can rely on:

```json
{
  "type": "about:blank",
  "title": "Conflict",
  "status": 409,
  "detail": "Payment b7ae6fcc-… cannot move from CANCELLED to PROCESSING",
  "instance": "/api/v1/payments/b7ae6fcc-…/status",
  "code": "INVALID_STATUS_TRANSITION",
  "timestamp": "2026-11-13T17:49:02.105Z",
  "currentStatus": "CANCELLED",
  "requestedStatus": "PROCESSING",
  "allowedStatuses": []
}
```

| Status | `code` | When |
|---|---|---|
| 400 | `VALIDATION_FAILED` | A field breaks a rule; `errors` lists `{field, message}` for each |
| 400 | `MALFORMED_REQUEST` | Invalid JSON, an unknown enum value, or an id that is not a UUID |
| 400 | `UNSUPPORTED_SORT_PROPERTY` | `sort` names a field outside the list above |
| 404 | `PAYMENT_NOT_FOUND` | No payment with that id |
| 409 | `INVALID_STATUS_TRANSITION` | The lifecycle doesn't allow the move |
| 409 | `DUPLICATE_REFERENCE` | The reference is already used |
| 409 | `CONCURRENT_UPDATE` | Someone else changed the payment at the same moment; re-read and retry |
| 409 | `DATA_CONFLICT` | A database constraint caught a race (e.g. two identical creates at once) |
| 422 | `IDEMPOTENCY_KEY_REUSED` | Same `Idempotency-Key`, different body |
| 500 | `INTERNAL_ERROR` | Anything unexpected; details are logged, never returned |

## Project structure

Organised by feature first, then by layer. Each layer only depends on the one below it.

```
src/main/java/com/learning/paymentservice/
├─ payment/
│  ├─ web/          PaymentController, dto/ (request & response records)
│  ├─ service/      PaymentService (interface), PaymentServiceImpl
│  ├─ domain/       Payment, PaymentStatusHistory, PaymentStatus (lifecycle rules), PaymentMethod
│  ├─ repository/   Spring Data repositories, PaymentSpecifications, PaymentFilter
│  ├─ mapper/       PaymentMapper (DTO ↔ entity)
│  └─ exception/    Business exceptions, each with a code and category
└─ common/
   ├─ exception/    GlobalExceptionHandler, BusinessException, ErrorCategory
   ├─ validation/   @IsoCurrencyCode
   ├─ web/          PageResponse
   └─ config/       JPA auditing, OpenAPI
src/main/resources/db/migration/   Flyway migrations (never edit one that has run; add V2, V3…)
```

Design choices worth knowing:

- **The domain enforces its own rules.** `Payment` has no setters; `Payment.create` validates, and `changeStatus` checks the lifecycle and returns the history entry to save. The transition table is an exhaustive `switch` in `PaymentStatus`, so a new status won't compile until its moves are declared.
- **Entities never leave the service.** The API only speaks DTO records.
- **Errors are data, not HTTP.** Exceptions carry an `ErrorCategory`; only the global handler knows HTTP status codes. Adding an exception needs no handler change.
- **Optimistic locking** (`@Version`) stops two simultaneous updates from silently overwriting each other.
- **Money** is `BigDecimal` in `NUMERIC(19,4)` with an ISO currency; times are UTC `Instant`s.
- **The schema belongs to Flyway.** Hibernate only validates it (`ddl-auto: validate`), and the database also enforces amounts, currencies, statuses and uniqueness with constraints.

## Tests

```bash
./mvnw verify
```

Docker must be running: repository and end-to-end tests start their own throwaway PostgreSQL through Testcontainers, so they never touch your local database.

| Layer | Test | Style |
|---|---|---|
| Domain | `PaymentStatusTest`, `PaymentTest` | Plain JUnit 5; all 36 status pairs checked |
| DTOs & mapper | `RequestValidationTest`, `PaymentMapperTest` | Bean Validation without Spring |
| Repository | `PaymentRepositoryTest`, `PaymentStatusHistoryRepositoryTest` | `@DataJpaTest` + Testcontainers |
| Service | `PaymentServiceImplTest` | Mockito, repositories mocked |
| Web | `PaymentControllerTest`, `ErrorResponseTest` | `@WebMvcTest` + `@MockitoBean` |
| End to end | `PaymentApiIntegrationTest` | `@SpringBootTest` over real HTTP + Testcontainers |

## Not done yet

- **Authentication.** Every change is recorded as made by `api`; the history's `changedBy` should come from the authenticated user.
- **Amount formatting.** Amounts are returned with the column's 4 decimals (`1500.5000`), not the currency's usual 2.
