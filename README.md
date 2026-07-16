# API Gateway

Single entry point for the system. Routes traffic, validates JWTs, and orchestrates the one workflow that spans multiple services: registration.

Part of a five-service stack — see [`k8s`](https://github.com/timode-6/k8s) for how it's deployed alongside [`auth-service`](https://github.com/timode-6/auth-service), [`user-service`](https://github.com/timode-6/user-service), [`order-service`](https://github.com/timode-6/order-service), and [`payment-service`](https://github.com/timode-6/payment-service).

## Responsibilities

- Routes incoming requests to Auth, User, Order, and Payment services.
- Validates JWTs on every request and checks a Redis-backed blacklist for revoked ones.
- Stamps every outbound request to internal services with an `X-Internal-Secret` header, so those services can reject anything that didn't come through the gateway.

## Registration saga

Registration spans two services, so the gateway runs it as an orchestrated saga instead of leaving consistency to chance:

1. Create the user profile in User Service → get back a `userId`.
2. Register credentials in Auth Service with that `userId`.
3. If step 2 fails, roll the user back with retries (`Retry.backoff`, retrying only on 5xx/`IOException` — not on 4xx, which won't fix itself by waiting).
4. If the rollback itself keeps failing after all retries, the failure is pushed onto a Redis Stream (`dlq:rollback-failed`) rather than just logged and forgotten.
5. A background consumer, in an `ops-group` consumer group (safe to run with multiple gateway replicas), polls that stream every 30 seconds and retries the rollback, acknowledging only once it actually succeeds.

**Known gap:** if the gateway process crashes between steps 1 and 2, there's no rollback state recorded yet, so the user profile can be left orphaned until it's cleaned up manually. A transactional outbox in User Service would close this.

## Stack

- Java 21, Spring Boot / Spring Cloud Gateway
- Project Reactor (reactive, non-blocking)
- Redis (token blacklist + rollback DLQ via Redis Streams)

## Running locally

```bash
./gradlew bootRun
```

or:

```bash
docker compose up --build
```