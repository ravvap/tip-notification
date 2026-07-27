# tip-commons-notification

Shared library so any TIP service can publish a notification event with one
call, without knowing the notification service's REST contract, Entra auth
mechanics, or idempotency key rules.

## Important: this calls REST, not Azure Event Hub

`NotificationPublishEngine` calls `POST {baseUrl}/api/v1/notification-events`
on the notification service - it does **not** publish to Event Hub directly.

This is intentional: `NotificationPublishService` (server-side) does
recipient resolution, severity/publisher validation, idempotency-key
deduplication, and template rendering - all of which must happen *before*
`NotificationDelivery` rows exist for the Event Hub consumer to act on.
Publishing straight to Event Hub from this jar would skip all of that and
produce messages with nothing in the database to dispatch.

## Add the dependency

```xml
<dependency>
    <groupId>gov.fdic.tip</groupId>
    <artifactId>tip-commons-notification</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

## Configure (application.yml)

```yaml
tip:
  notification-publish:
    base-url: https://tip-notification.internal.fdic.gov
    auth-mode: managed-identity          # or client-secret for local/dev
    token-scope: api://tip-notification-service/.default
    # tenant-id / client-id / client-secret: only needed if auth-mode=client-secret
```

`NotificationPublishClient` is now available to `@Autowire` anywhere.

## Usage (Spring Boot)

```java
@Service
public class RetentionStampingService {

    private final NotificationPublishClient notificationPublishClient;

    public RetentionStampingService(NotificationPublishClient notificationPublishClient) {
        this.notificationPublishClient = notificationPublishClient;
    }

    public void onRecordStamped(RetentionRecord record) {
        try {
            notificationPublishClient.publish(NotificationPublishRequest.builder()
                    .source("RETENTION_ETL")
                    .eventType("RETENTION_STAMPED")
                    // STABLE key - if this batch step reruns for the same record,
                    // the server recognizes it as the same event via
                    // findBySourceAndIdempotencyKey(...) instead of creating a duplicate.
                    .idempotencyKey("retention-stamp-" + record.getRecordId())
                    .recipientEmail(record.getOwnerEmail())
                    .context(Map.of("recordId", record.getRecordId(), "purgeDate", record.getPurgeDate()))
                    .build());
        } catch (NotificationPublishException e) {
            // Decide deliberately: usually log and continue rather than fail the
            // underlying business operation over a notification issue.
            log.warn("Failed to publish notification for recordId={}", record.getRecordId(), e);
        }
    }
}
```

## Usage (non-Spring / plain batch tooling)

```java
// Once at startup:
NotificationPublishUtil.configureWithManagedIdentity(
        "https://tip-notification.internal.fdic.gov",
        "api://tip-notification-service/.default");

// Anywhere after:
NotificationPublishUtil.publish(NotificationPublishRequest.builder()
        .source("QUARTZ_SCHEDULER")
        .eventType("JOB_FAILED")
        .idempotencyKey("job-failure-" + jobRunId)
        .recipientRole("SCHEDULER_ADMIN")
        .build());
```

## idempotencyKey - the one field to get right

- **One-shot operations** (a user action, a single API call): fine to derive
  from something unique to that call, or even random - it won't retry with
  the same identity.
- **Retryable operations** (a batch step that might rerun, a call your own
  code retries on timeout): MUST be a stable value derived from the source
  record/operation, or a retry becomes a second `NotificationEvent` server-side.

## severity / recipientEmail - both optional

- Omit `severity` to let the server use the event type's configured default.
- Omit `recipientEmail` for a broadcast to the event's configured audience
  (subject to the server's broadcast cap - see
  `resolveRecipients`/`getBroadcastCap()` in `NotificationPublishService`).

## Error handling

`publish()` throws a checked `NotificationPublishException` - this library
does not decide for you whether a failed publish should fail your operation.
A `409` response (idempotency key reused with a different payload) is a
caller bug, not a transient failure - don't blindly retry on that one.
