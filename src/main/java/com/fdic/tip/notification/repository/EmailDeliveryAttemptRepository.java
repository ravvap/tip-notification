package com.fdic.tip.notification.repository;

import com.fdic.tip.notification.entity.EmailDeliveryAttemptEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface EmailDeliveryAttemptRepository extends JpaRepository<EmailDeliveryAttemptEntity, UUID> {
}
