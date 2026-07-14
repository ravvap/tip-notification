package com.fdic.tip.notification.dto;

import com.fdic.tip.notification.entity.NotificationEntity;

import java.time.OffsetDateTime;
import java.util.UUID;

public record NotificationDto(
        UUID id,
        String noticeType,
        String title,
        String message,
        boolean read,
        OffsetDateTime createdAt,
        OffsetDateTime readAt
) {
    public static NotificationDto from(NotificationEntity e) {
        return new NotificationDto(
                e.getId(), e.getNoticeType(), e.getTitle(), e.getMessage(),
                e.isRead(), e.getCreatedAt(), e.getReadAt());
    }
}
