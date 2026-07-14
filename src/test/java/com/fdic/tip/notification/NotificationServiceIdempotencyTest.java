package com.fdic.tip.notification;

import com.fdic.tip.notification.dto.IncomingEventDto;
import com.fdic.tip.notification.dto.NotificationDto;
import com.fdic.tip.notification.service.NotificationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest
class NotificationServiceIdempotencyTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired
    NotificationService notificationService;

    @Test
    void replayedEventDoesNotCreateDuplicateNotification() {
        IncomingEventDto event = new IncomingEventDto(
                "evt-12345", "user-abc", "SYSTEM_ALERT", "Something happened", "Details here");

        NotificationDto first = notificationService.handleIncomingEvent(event);
        assertThat(first).isNotNull();

        // Simulate Event Hub redelivering the same event (at-least-once semantics)
        NotificationDto second = notificationService.handleIncomingEvent(event);

        List<NotificationDto> history = notificationService.getHistory("user-abc");
        assertThat(history).hasSize(1);
        assertThat(second == null || second.id().equals(first.id())).isTrue();
    }
}
