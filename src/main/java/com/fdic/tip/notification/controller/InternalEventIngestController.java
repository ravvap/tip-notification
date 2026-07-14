package com.fdic.tip.notification.controller;

import com.fdic.tip.notification.dto.IncomingEventDto;
import com.fdic.tip.notification.dto.NotificationDto;
import com.fdic.tip.notification.service.NotificationService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * TEST-ONLY endpoint. Real production ingestion now goes through
 * EventHubConsumerService, which consumes directly from Azure Event Hub.
 *
 * This REST endpoint remains as a way to exercise the pipeline (local
 * testing, Postman, integration tests) without a live Event Hub connection -
 * it calls the exact same NotificationService.handleIncomingEvent() method
 * the real consumer does, so behavior is identical either way.
 *
 * Locked down via SecurityConfig - should never be reachable in production
 * beyond what your ServicePrincipalAllowlist explicitly permits, if kept
 * enabled at all in that environment.
 */
@RestController
@RequestMapping("/internal/events")
public class InternalEventIngestController {

    private final NotificationService notificationService;

    public InternalEventIngestController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @PostMapping
    public ResponseEntity<NotificationDto> ingest(@Valid @RequestBody IncomingEventDto event) {
        NotificationDto result = notificationService.handleIncomingEvent(event);
        return ResponseEntity.ok(result);
    }
}
