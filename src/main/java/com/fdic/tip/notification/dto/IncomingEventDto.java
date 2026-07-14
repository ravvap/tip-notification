package com.fdic.tip.notification.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Shape of the event consumed from Azure Event Hub (step 2 in the flow diagram).
 * eventId MUST be a stable, unique identifier from the source system - it is the
 * idempotency key checked in NotificationService before anything is persisted.
 */
public record IncomingEventDto(
        @NotBlank String eventId,
        @NotBlank String userId,
        @NotBlank String noticeType,
        @NotBlank String title,
        String message
) {
}
