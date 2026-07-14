package com.fdic.tip.notification.repository;

import com.fdic.tip.notification.entity.ProcessedEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEventEntity, String> {
    // existsById(eventId) is the idempotency check used in NotificationService
}
