package gov.fdic.tip.service;

import java.util.UUID;

/**
 * Fired from NotificationPublishService.createEvent() right after
 * eventRepository.save(...)/deliveryRepository.saveAll(...). Deliberately
 * NOT the trigger for the actual Event Hub publish - see
 * NotificationEventOutboxPublisher, which listens for this AFTER the
 * surrounding @Transactional method commits. Publishing to Event Hub inside
 * the transaction (before commit) would risk telling the consumer about a
 * NotificationEvent that a later rollback makes disappear.
 */
public record NotificationEventPersistedEvent(UUID notificationEventId) {
}
