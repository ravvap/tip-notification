package com.fdic.tip.notification.service;

import com.fdic.tip.notification.dto.EmailRequestDto;
import com.fdic.tip.notification.dto.IncomingEventDto;
import com.fdic.tip.notification.dto.NotificationDto;
import com.fdic.tip.notification.entity.EmailDeliveryAttemptEntity;
import com.fdic.tip.notification.entity.NotificationEntity;
import com.fdic.tip.notification.entity.ProcessedEventEntity;
import com.fdic.tip.notification.repository.EmailDeliveryAttemptRepository;
import com.fdic.tip.notification.repository.NotificationRepository;
import com.fdic.tip.notification.repository.ProcessedEventRepository;
import com.fdic.tip.notification.service.email.EmailDeliveryException;
import com.fdic.tip.notification.service.email.EmailNotifier;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

@Slf4j
@Service
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final EmailDeliveryAttemptRepository emailDeliveryAttemptRepository;
    private final EmailNotifier emailNotifier;

    public NotificationService(NotificationRepository notificationRepository,
                                ProcessedEventRepository processedEventRepository,
                                EmailDeliveryAttemptRepository emailDeliveryAttemptRepository,
                                EmailNotifier emailNotifier) {
        this.notificationRepository = notificationRepository;
        this.processedEventRepository = processedEventRepository;
        this.emailDeliveryAttemptRepository = emailDeliveryAttemptRepository;
        this.emailNotifier = emailNotifier;
    }

    /**
     * Entry point called by whatever consumes Azure Event Hub (step 2/3/4 in
     * the flow diagram). Idempotency guard first, then persist. No pg_notify,
     * no DB trigger/function - live delivery to connected clients is handled
     * separately by NotificationPollingService, which polls this table on an
     * interval per instance. This method's only job is to make the write
     * durable.
     *
     * Email dispatch is kicked off async AFTER the transaction commits, so a
     * slow/failing email provider never holds up the DB write or blocks the
     * event consumer thread.
     */
    @Transactional
    public NotificationDto handleIncomingEvent(IncomingEventDto event) {
        if (processedEventRepository.existsById(event.eventId())) {
            log.info("Duplicate event delivery detected, eventId={} - skipping", event.eventId());
            // Return existing notification if present, otherwise this is a no-op replay.
            return notificationRepository.findByUserIdOrderByCreatedAtDesc(event.userId())
                    .stream()
                    .filter(n -> n.getEventId().equals(event.eventId()))
                    .findFirst()
                    .map(NotificationDto::from)
                    .orElse(null);
        }

        try {
            processedEventRepository.save(new ProcessedEventEntity(event.eventId(), null));
        } catch (DataIntegrityViolationException dup) {
            // Race: two threads/instances processed the same event ID concurrently.
            log.info("Concurrent duplicate event detected, eventId={} - skipping", event.eventId());
            return null;
        }

        NotificationEntity saved = notificationRepository.save(NotificationEntity.builder()
                .userId(event.userId())
                .eventId(event.eventId())
                .noticeType(event.noticeType())
                .title(event.title())
                .message(event.message())
                .read(false)
                .build());

        dispatchEmailAsync(saved);

        return NotificationDto.from(saved);
    }

    @Async
    public void dispatchEmailAsync(NotificationEntity notification) {
        EmailRequestDto request = new EmailRequestDto(
                notification.getId(),
                notification.getUserId(),
                notification.getTitle(),
                notification.getMessage(),
                notification.getNoticeType());

        try {
            emailNotifier.send(request);
            recordEmailAttempt(notification.getId(), EmailDeliveryAttemptEntity.Status.SENT, null);
        } catch (EmailDeliveryException e) {
            log.error("Email delivery failed for notificationId={}", notification.getId(), e);
            recordEmailAttempt(notification.getId(), EmailDeliveryAttemptEntity.Status.FAILED, e.getMessage());
            // TODO: enqueue for retry with backoff, or route to a DLQ table/topic
            // after N attempts. Left as an explicit extension point since retry
            // policy (max attempts, backoff curve) is a product decision.
        }
    }

    private void recordEmailAttempt(UUID notificationId, EmailDeliveryAttemptEntity.Status status, String error) {
        emailDeliveryAttemptRepository.save(EmailDeliveryAttemptEntity.builder()
                .notificationId(notificationId)
                .status(status)
                .errorDetail(error)
                .build());
    }

    public List<NotificationDto> getHistory(String userId) {
        return notificationRepository.findByUserIdOrderByCreatedAtDesc(userId)
                .stream().map(NotificationDto::from).toList();
    }

    /**
     * Used by an instance to self-heal after a stretch where its
     * NotificationPollingService may have missed a tick (e.g. GC pause,
     * temporary DB connectivity issue) or after an SSE reconnect.
     */
    public List<NotificationDto> reconcileMissed(String userId, OffsetDateTime since) {
        return notificationRepository.findMissedSince(userId, since)
                .stream().map(NotificationDto::from).toList();
    }

    @Transactional
    public NotificationDto markAsRead(UUID id, String userId) {
        int updated = notificationRepository.markAsRead(id, userId);
        if (updated == 0) {
            throw new NoSuchElementException("Notification not found for this user: " + id);
        }
        return notificationRepository.findByIdAndUserId(id, userId)
                .map(NotificationDto::from)
                .orElseThrow();
    }
}
