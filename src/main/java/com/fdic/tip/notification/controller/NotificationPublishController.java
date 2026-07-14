package com.fdic.tip.notification.controller;

import com.fdic.tip.notification.dto.PublishNotificationRequest;
import com.fdic.tip.notification.dto.PublishNotificationResponse;
import com.fdic.tip.notification.eventhub.EventHubPublisherService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The endpoint other TIP services (REST APIs, batch jobs, schedulers) call
 * to raise a notification - instead of each one embedding an Event Hub
 * client. This service is the only thing in TIP with Event Hub producer
 * credentials; everyone else just calls this over HTTP.
 *
 * Response is 202 Accepted, not 200/201 - publishing to Event Hub is
 * fire-and-forget from the caller's perspective. A 202 here means "Event Hub
 * accepted it", NOT "the notification has been persisted/delivered" - that
 * happens asynchronously once EventHubConsumerService picks it up. Callers
 * who need to confirm persistence should poll GET /api/v1/notifications or
 * treat this as eventually consistent.
 */
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationPublishController {

    private final EventHubPublisherService publisherService;

    public NotificationPublishController(EventHubPublisherService publisherService) {
        this.publisherService = publisherService;
    }

    @PostMapping("/publish")
    public ResponseEntity<PublishNotificationResponse> publish(@Valid @RequestBody PublishNotificationRequest request) {
        String eventId = publisherService.publish(request);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new PublishNotificationResponse(eventId, "ACCEPTED"));
    }
}
