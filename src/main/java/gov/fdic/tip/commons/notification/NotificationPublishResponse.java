package gov.fdic.tip.commons.notification;

import java.time.Instant;
import java.util.UUID;

/**
 * Mirrors PublishNotificationEventResponseDTO. `duplicate` is true when the
 * server matched an existing NotificationEvent by source+idempotencyKey
 * (your call was a safe retry, not a new event).
 */
public record NotificationPublishResponse(
        UUID notificationEventId,
        Instant createdAt,
        boolean duplicate
) {
}
