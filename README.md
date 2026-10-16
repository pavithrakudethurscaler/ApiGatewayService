# API Gateway

Single entry point for the ecom platform (HLD §2). Port **8000**. Built with Spring Cloud Gateway (reactive).
Clients call `http://localhost:8000/api/v1/...` instead of each service's own port.

## Routes

| Path | Service |
|---|---|
| `/api/v1/products/**`, `/api/v1/categories/**` | Product (8080) |
| `/api/v1/users/**`, `/api/v1/admin/users/**` | User (8082) |
| `/api/v1/payments/**` (incl. the Razorpay webhook) | Payment (8083) |
| `/api/v1/orders/**`, `/api/v1/admin/orders/**` | Order (8084) |
| `/api/v1/cart/**` | Cart (8085) |
| `/api/v1/notifications/**` | Notification (8086) |

`/internal/**` is never routed (404), and any `X-Internal-Token` header from a client is removed.

## What the gateway adds

- **Rate limiting** per client IP (token bucket; default burst 100, refill 20/s) → 429 with `Retry-After`. The payment webhook is exempt.
- **Request IDs**: every request gets an `X-Request-Id` (a safe client-supplied one is kept), sent to the service and returned to the client, and logged in one access-log line.
- **CORS** for the configured browser origins.
- **Clean errors** in the platform format: unknown path 404, service down 503, service too slow 504.

Authentication is still checked by each service (bearer token validated with the User Service).

## Configuration

| Env var | Default |
|---|---|
| `PRODUCTSERVICE_URL` … `NOTIFICATIONSERVICE_URL` | `http://localhost:8080` … `:8086` |
| `RATE_LIMIT_ENABLED`, `RATE_LIMIT_CAPACITY`, `RATE_LIMIT_REFILL_PER_SECOND` | `true`, `100`, `20` |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:3000` |

## Run

```bash
./gradlew build
java -jar build/libs/apigateway-0.0.1-SNAPSHOT.jar
```
