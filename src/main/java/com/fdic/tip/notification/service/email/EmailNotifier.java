package com.fdic.tip.notification.service.email;

import com.fdic.tip.notification.dto.EmailRequestDto;

/**
 * Integration seam for email delivery. NotificationService depends only on
 * this interface, never on a specific email provider's client - swap
 * RestEmailClient for any implementation that talks to your existing email-api
 * without touching notification logic.
 *
 * Implementations should throw EmailDeliveryException on failure rather than
 * swallowing it - NotificationService uses that to record the attempt for
 * audit/retry (see EmailDeliveryAttemptEntity).
 */
public interface EmailNotifier {
    void send(EmailRequestDto request) throws EmailDeliveryException;
}
