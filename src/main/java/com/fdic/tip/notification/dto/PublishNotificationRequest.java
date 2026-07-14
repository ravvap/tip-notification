package com.fdic.tip.notification.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for POST /api/v1/notifications/publish.
 *
 * eventId is OPTIONAL. If omitted, a random one is generated server-side.
 * Callers whose own operation might retry (e.g. a batch step that could
 * rerun) should supply a STABLE eventId derived from their source record -
 * otherwise a retry produces a duplicate notification, since the downstream
 * idempotency guard only protects you if the same logical event carries the
 * same ID both times.
 */
public record PublishNotificationRequest(
        String eventId,
        @NotBlank String userId,
        @NotBlank String noticeType,
        @NotBlank String title,
        String message
) {
}
