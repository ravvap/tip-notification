package gov.fdic.tip.web.rest;

// This file lives in a DIFFERENT Spring Boot service (e.g. tip-cm-retention,
// tip-scheduler, or any other REST API) - NOT in tip-notification or
// tip-commons-notification itself. It's included here purely as a reference
// showing exactly what another team writes once they've added the
// tip-commons-notification jar as a dependency.

import gov.fdic.tip.commons.notification.NotificationPublishClient;
import gov.fdic.tip.commons.notification.NotificationPublishException;
import gov.fdic.tip.commons.notification.NotificationPublishRequest;
import gov.fdic.tip.commons.notification.NotificationPublishResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Example controller in a consuming service. NOTHING beyond adding the
 * Maven dependency and a few application.yml properties (see this project's
 * README) was needed to get NotificationPublishClient injectable here -
 * that's the whole point of TipCommonsNotificationAutoConfiguration.
 */
@RestController
@RequestMapping("/api/v1/documents")
public class DocumentClassificationController {

    private static final Logger LOG = LoggerFactory.getLogger(DocumentClassificationController.class);

    private final NotificationPublishClient notificationPublishClient;
    // ... this service's own existing dependencies (DocumentService, etc.) ...

    public DocumentClassificationController(NotificationPublishClient notificationPublishClient
                                              /* , DocumentService documentService, ... */) {
        this.notificationPublishClient = notificationPublishClient;
    }

    @PostMapping("/{documentId}/classify")
    public ResponseEntity<Void> classify(@PathVariable String documentId) {
        // ... existing classification logic (unchanged) ...
        String ownerEmail = "owner@fdic.gov"; // however this service already resolves it

        try {
            NotificationPublishResponse response = notificationPublishClient.publish(
                    NotificationPublishRequest.builder()
                            .source("CM_RETENTION")
                            .eventType("DOCUMENT_CLASSIFIED")
                            .idempotencyKey("doc-classified-" + documentId)
                            .recipientEmail(ownerEmail)
                            .context(Map.of("documentId", documentId))
                            .build());

            LOG.info("Notification published, notificationEventId={}, duplicate={}",
                    response.notificationEventId(), response.duplicate());

        } catch (NotificationPublishException e) {
            // Deliberate choice: log and continue rather than fail the classification
            // request over a notification issue - the classification itself already
            // succeeded and shouldn't be rolled back for this.
            LOG.warn("Failed to publish DOCUMENT_CLASSIFIED notification for documentId={}", documentId, e);
        }

        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }
}
