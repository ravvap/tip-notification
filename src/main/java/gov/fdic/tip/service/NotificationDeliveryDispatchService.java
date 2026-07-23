package gov.fdic.tip.service;

import gov.fdic.tip.domain.NotificationChannel;
import gov.fdic.tip.domain.NotificationDelivery;
import gov.fdic.tip.domain.NotificationDeliveryStatus;
import gov.fdic.tip.domain.NotificationRecipient;
import gov.fdic.tip.repository.NotificationDeliveryRepository;
import gov.fdic.tip.repository.NotificationRecipientRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// TODO: fix this import to your existing email-service library's real package
// import gov.fdic.tip.email.EmailService;
// import gov.fdic.tip.email.EmailMessage;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Executes channel-specific delivery for a NotificationEvent's PENDING
 * deliveries. Called by NotificationEventHubConsumer after the event is
 * picked up off Event Hub - this class does not touch Event Hub itself, it
 * just knows how to turn a PENDING NotificationDelivery row into a sent
 * message (or a FAILED/terminal one) per channel.
 *
 * IN_APP deliveries are NOT processed here - NotificationPublishService
 * already sets those straight to DELIVERED at creation time (see
 * createDelivery's ternary), since "delivered" for IN_APP just means the row
 * exists and is visible to GET /notifications / SSE poll. This class only
 * has work to do for channels that need active dispatch - EMAIL today, with
 * a clear seam to add SMS/other channels later via the same switch.
 */
@Service
public class NotificationDeliveryDispatchService {

    private static final Logger LOG = LoggerFactory.getLogger(NotificationDeliveryDispatchService.class);
    private static final int MAX_ATTEMPTS = 3;

    private final NotificationDeliveryRepository deliveryRepository;
    private final NotificationRecipientRepository recipientRepository;
    // private final EmailService emailService; // uncomment once the import above is fixed

    public NotificationDeliveryDispatchService(NotificationDeliveryRepository deliveryRepository,
                                                 NotificationRecipientRepository recipientRepository
                                                 /* , EmailService emailService */) {
        this.deliveryRepository = deliveryRepository;
        this.recipientRepository = recipientRepository;
        // this.emailService = emailService;
    }

    /**
     * Entry point called by the Event Hub consumer for a given
     * notificationEventId. Loads every delivery still PENDING for that event
     * and dispatches each one per its channel.
     *
     * IMPORTANT idempotency note: this method is safe to call more than once
     * for the same eventId (Event Hub redelivery, consumer restart, etc.)
     * because each delivery is re-checked for PENDING status right before
     * processing (see dispatchOne) - anything already DELIVERED, FAILED
     * (terminal), or SUPPRESSED from a prior run is skipped, not resent.
     */
    public void dispatchPendingDeliveries(UUID notificationEventId) {
        List<NotificationDelivery> pending =
                deliveryRepository.findByNotificationEventIdAndStatus(notificationEventId, NotificationDeliveryStatus.PENDING);

        if (pending.isEmpty()) {
            LOG.debug("No PENDING deliveries for notificationEventId={}", notificationEventId);
            return;
        }

        for (NotificationDelivery delivery : pending) {
            dispatchOne(delivery);
        }
    }

    @Transactional
    public void dispatchOne(NotificationDelivery delivery) {
        // Re-check status inside the transaction - guards against a race where
        // two consumer threads/instances picked up the same redelivered event.
        NotificationDelivery current = deliveryRepository.findById(delivery.getId()).orElse(null);
        if (current == null || current.getStatus() != NotificationDeliveryStatus.PENDING) {
            LOG.debug("Delivery {} no longer PENDING (status={}), skipping",
                    delivery.getId(), current == null ? "DELETED" : current.getStatus());
            return;
        }

        switch (current.getChannel()) {
            case EMAIL -> dispatchEmail(current);
            case IN_APP -> {
                // Should not normally reach here - IN_APP is set to DELIVERED at
                // creation. Defensive log only, in case that invariant changes.
                LOG.warn("IN_APP delivery {} was PENDING at dispatch time - unexpected, marking DELIVERED", current.getId());
                markDelivered(current);
            }
            default -> {
                LOG.warn("Unsupported channel {} for delivery {} - marking FAILED", current.getChannel(), current.getId());
                markFailed(current, "UNSUPPORTED_CHANNEL");
            }
        }
    }

    private void dispatchEmail(NotificationDelivery delivery) {
        NotificationRecipient recipient = recipientRepository.findById(delivery.getRecipientId()).orElse(null);
        if (recipient == null || recipient.getRecipientEmail() == null || recipient.getRecipientEmail().isBlank()) {
            // Should already have been caught as MISSING_EMAIL at creation, but
            // guard again here in case the recipient record changed since.
            markFailed(delivery, "MISSING_EMAIL");
            return;
        }

        try {
            // emailService.sendMessage(EmailMessage.builder()
            //         .to(recipient.getRecipientEmail())
            //         .subject(delivery.getRenderedSubject())
            //         .content(delivery.getRenderedBodyHtml() != null
            //                 ? delivery.getRenderedBodyHtml()
            //                 : delivery.getRenderedBodyText())
            //         .build());

            // Placeholder until the real call above is uncommented:
            throw new UnsupportedOperationException(
                    "Wire the real emailService.sendMessage(...) call above with your library's actual import.");

        } catch (Exception e) {
            LOG.error("Email dispatch failed for deliveryId={}, recipientEmail={}",
                    delivery.getId(), recipient.getRecipientEmail(), e);
            handleFailure(delivery, e.getMessage());
            return;
        }

        markDelivered(delivery);
    }

    private void markDelivered(NotificationDelivery delivery) {
        delivery.setStatus(NotificationDeliveryStatus.DELIVERED);
        delivery.setUpdatedAt(Instant.now());
        deliveryRepository.save(delivery);
        LOG.info("Delivery {} marked DELIVERED (channel={})", delivery.getId(), delivery.getChannel());
    }

    private void markFailed(NotificationDelivery delivery, String reason) {
        delivery.setStatus(NotificationDeliveryStatus.FAILED); // TODO: confirm this value exists on NotificationDeliveryStatus - add it if not
        delivery.setStatusReason(reason);
        delivery.setUpdatedAt(Instant.now());
        deliveryRepository.save(delivery);
        LOG.warn("Delivery {} marked FAILED, reason={}", delivery.getId(), reason);
    }

    /**
     * On a transient failure (email-api threw), retry up to MAX_ATTEMPTS by
     * leaving status PENDING so the next poll/consumer pass picks it up
     * again - only mark terminal FAILED once attempts are exhausted.
     */
    private void handleFailure(NotificationDelivery delivery, String reason) {
        int attempts = delivery.getAttemptCount() == null ? 1 : delivery.getAttemptCount() + 1;
        delivery.setAttemptCount(attempts);
        delivery.setUpdatedAt(Instant.now());

        if (attempts >= MAX_ATTEMPTS) {
            delivery.setStatus(NotificationDeliveryStatus.FAILED);
            delivery.setStatusReason(reason);
            LOG.error("Delivery {} exhausted {} attempts, marking terminal FAILED", delivery.getId(), attempts);
        } else {
            // Left as PENDING - a retry pass (scheduled poll or next Event Hub
            // redelivery) will pick this back up. See class javadoc on
            // dispatchPendingDeliveries for why re-processing PENDING rows is safe.
            LOG.warn("Delivery {} failed attempt {}/{}, will retry", delivery.getId(), attempts, MAX_ATTEMPTS);
        }
        deliveryRepository.save(delivery);
    }
}
