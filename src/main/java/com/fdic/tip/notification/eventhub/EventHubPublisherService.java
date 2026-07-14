package com.fdic.tip.notification.eventhub;

import com.azure.core.credential.TokenCredential;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.messaging.eventhubs.EventData;
import com.azure.messaging.eventhubs.EventDataBatch;
import com.azure.messaging.eventhubs.EventHubClientBuilder;
import com.azure.messaging.eventhubs.EventHubProducerClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fdic.tip.notification.dto.PublishNotificationRequest;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Publishes to Event Hub on behalf of NotificationPublishController. This is
 * the ONLY place in the whole TIP ecosystem that needs Event Hub producer
 * credentials - other services just call the REST endpoint, they never talk
 * to Event Hub directly. Reuses the same EventHubProperties as the consumer
 * side (EventHubConsumerService) since both point at the same hub.
 *
 * EventHubProducerClient is thread-safe and expensive to create - built once
 * at startup and reused for every publish, not created per-request.
 */
@Slf4j
@Component
public class EventHubPublisherService {

    private final EventHubProperties properties;
    private final ObjectMapper objectMapper;
    private EventHubProducerClient producerClient;

    public EventHubPublisherService(EventHubProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        EventHubClientBuilder builder = new EventHubClientBuilder();

        if ("managed-identity".equalsIgnoreCase(properties.getAuthMode())) {
            TokenCredential credential = new DefaultAzureCredentialBuilder().build();
            builder.credential(properties.getFullyQualifiedNamespace(), properties.getEventHubName(), credential);
        } else {
            builder.connectionString(properties.getConnectionString(), properties.getEventHubName());
        }

        producerClient = builder.buildProducerClient();
        log.info("EventHubPublisherService ready - eventHub={}", properties.getEventHubName());
    }

    @PreDestroy
    public void shutdown() {
        if (producerClient != null) {
            producerClient.close();
        }
    }

    /**
     * Publishes and returns the eventId actually used (either the caller's,
     * or a generated one if they omitted it).
     */
    public String publish(PublishNotificationRequest request) {
        String eventId = (request.eventId() != null && !request.eventId().isBlank())
                ? request.eventId()
                : UUID.randomUUID().toString();

        try {
            String json = objectMapper.writeValueAsString(toWireFormat(eventId, request));

            EventDataBatch batch = producerClient.createBatch();
            if (!batch.tryAdd(new EventData(json))) {
                throw new IllegalStateException("Event too large for a single batch, eventId=" + eventId);
            }
            producerClient.send(batch);

            log.info("Published notification event to Event Hub, eventId={}", eventId);
            return eventId;
        } catch (Exception e) {
            throw new EventHubPublishException("Failed to publish to Event Hub, eventId=" + eventId, e);
        }
    }

    // Field names MUST match IncomingEventDto, since EventHubConsumerService
    // deserializes exactly this shape when reading it back off the hub.
    private Map<String, Object> toWireFormat(String eventId, PublishNotificationRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("eventId", eventId);
        body.put("userId", request.userId());
        body.put("noticeType", request.noticeType());
        body.put("title", request.title());
        body.put("message", request.message());
        return body;
    }
}
