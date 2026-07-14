package com.fdic.tip.notification.dto;

import java.util.UUID;

/**
 * Everything the email integration needs to send a notification email.
 * Deliberately decoupled from NotificationEntity so the email integration
 * point doesn't depend on JPA internals.
 */
public record EmailRequestDto(
        UUID notificationId,
        String recipientUserId,   // resolved to an email address by the EmailNotifier impl, or pass-through if your email-api resolves it itself
        String subject,
        String body,
        String noticeType
) {
}
