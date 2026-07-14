package com.fdic.tip.notification.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "email_delivery_attempt")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmailDeliveryAttemptEntity {

    public enum Status { SENT, FAILED, RETRYING, DEAD_LETTERED }

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "notification_id", nullable = false)
    private UUID notificationId;

    @Column(name = "attempted_at", nullable = false)
    private OffsetDateTime attemptedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "error_detail")
    private String errorDetail;

    @PrePersist
    void onCreate() {
        if (attemptedAt == null) {
            attemptedAt = OffsetDateTime.now();
        }
    }
}
