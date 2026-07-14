# Testing tip-notification locally (no real Azure Event Hub needed)

Uses Microsoft's official **Azure Event Hubs Emulator** (Docker) instead of a
real Event Hub namespace, plus Postgres and the emulator's own Azurite
dependency - all spun up with one command.

## 1. Start the local infrastructure

```bash
docker compose -f docker/docker-compose.eventhub-emulator.yml up -d
```

Wait for the emulator to report readiness:

```bash
docker logs eventhubs-emulator | grep "Emulator Service is Successfully Up"
```

This starts three containers: the Event Hubs emulator, its Azurite
dependency, and Postgres (`tip_dev` database, matching `application-local.yml`).

## 2. Run migrations and start the app

```bash
mvn flyway:migrate -Dflyway.url=jdbc:postgresql://localhost:5432/tip_dev \
    -Dflyway.user=tip_app -Dflyway.password=changeme

mvn spring-boot:run -Dspring-boot.run.profiles=local
```

The `local` profile (`application-local.yml`) points the app at the emulator
using **connection-string auth** - the emulator doesn't support Entra ID /
managed identity, so `tip.event-hub.auth-mode=connection-string` is required
locally even though `managed-identity` is the default/recommended mode
everywhere else.

It also activates `LocalSecurityConfig`, which disables JWT auth entirely so
you can hit endpoints from Postman/curl without minting a token. **This
config is profile-gated to `local` only and is never active in a deployed
environment.**

## 3. Publish a test notification

```bash
curl -X POST http://localhost:8080/api/v1/notifications/publish \
  -H "Content-Type: application/json" \
  -d '{
        "userId": "test-user-1",
        "noticeType": "SYSTEM_ALERT",
        "title": "Test notification",
        "message": "Hello from the emulator"
      }'
```

Expect `202 Accepted` with the `eventId` used.

## 4. Watch it flow through

- **Consumer picked it up**: app logs should show `EventHubConsumerService`
  processing the event within a second or two.
- **Persisted**: connect to Postgres and check the `notification` table:
  ```sql
  psql -h localhost -U tip_app -d tip_dev -c "select * from notification order by created_at desc limit 5;"
  ```
- **Live push**: open an SSE connection first, THEN publish, to see the live
  path work end-to-end:
  ```bash
  curl -N "http://localhost:8080/api/v1/notifications/stream?token=x&userId=test-user-1"
  ```
  Leave that running in one terminal, then run step 3's curl in another - you
  should see the SSE event arrive within ~2 seconds (the polling interval in
  `NotificationPollingService`).

## Known local-only limitations

- `GET /api/v1/notifications` and `PATCH /{id}/read` expect a
  `JwtAuthenticationToken` (they read `userId` from the token claims). With
  `LocalSecurityConfig` active there's no JWT at all, so those two endpoints
  aren't directly curl-able as-is locally. Either query Postgres directly to
  verify persistence (step 4 above), or temporarily add a test-only filter
  that injects a fake `JwtAuthenticationToken` if you need to exercise those
  specific endpoints end-to-end.
- The emulator has functional gaps vs. real Event Hubs (no Entra ID, no
  virtual network integration, enforced quotas) - it's for logic/wiring
  verification, not a substitute for testing against a real namespace before
  a deployment.
- `EventHubPublisherService` and `EventHubConsumerService` both read
  `tip.event-hub.*` - `application-local.yml` overrides those to
  connection-string mode; don't forget to switch back for any non-local profile.

## Tearing down

```bash
docker compose -f docker/docker-compose.eventhub-emulator.yml down -v
```
