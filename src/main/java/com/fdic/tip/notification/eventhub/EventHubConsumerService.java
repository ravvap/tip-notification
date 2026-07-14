package com.fdic.tip.notification.eventhub;

import com.azure.core.credential.TokenCredential;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.messaging.eventhubs.CheckpointStore;
import com.azure.messaging.eventhubs.EventProcessorClient;
import com.azure.messaging.eventhubs.EventProcessorClientBuilder;
import com.azure.messaging.eventhubs.checkpointstore.blob.BlobCheckpointStore;
import com.azure.messaging.eventhubs.models.ErrorContext;
import com.azure.messaging.eventhubs.models.EventContext;
import com.azure.storage.blob.BlobContainerAsyncClient;
import com.azure.storage.blob.BlobContainerClientBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fdic.tip.notification.dto.IncomingEventDto;
import com.fdic.tip.notification.service.NotificationService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Consumes from Azure Event Hub and hands each event to NotificationService.
 * This REPLACES InternalEventIngestController as the real production entry
 * point - that controller stays only as a manual/test-only way to exercise
 * the pipeline without a live Event Hub connection.
 *
 * Consumer group: use a DEDICATED consumer group for this service (not
 * $Default) if any other service also reads this same Event Hub, so each
 * consumer maintains its own independent read position.
 *
 * Checkpointing: checkpoint is updated AFTER handleIncomingEvent() returns
 * successfully - i.e. after the notification is durably persisted (or
 * recognized as a duplicate). If the app crashes mid-processing, Event Hub
 * will redeliver the event on restart; NotificationService's idempotency
 * guard (processed_event table) is what makes that safe to replay.
 */
@Slf4j
@Component
public class EventHubConsumerService {

    private final EventHubProperties properties;
    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;

    private EventProcessorClient processorClient;

    public EventHubConsumerService(EventHubProperties properties,
                                    NotificationService notificationService,
                                    ObjectMapper objectMapper) {
        this.properties = properties;
        this.notificationService = notificationService;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void start() {
        CheckpointStore checkpointStore = buildCheckpointStore();

        EventProcessorClientBuilder builder = new EventProcessorClientBuilder()
                .consumerGroup(properties.getConsumerGroup())
                .checkpointStore(checkpointStore)
                .processEvent(this::handleEvent)
                .processError(this::handleError);

        if ("managed-identity".equalsIgnoreCase(properties.getAuthMode())) {
            TokenCredential credential = new DefaultAzureCredentialBuilder().build();
            builder.credential(properties.getFullyQualifiedNamespace(), properties.getEventHubName(), credential);
        } else {
            builder.connectionString(properties.getConnectionString(), properties.getEventHubName());
        }

        processorClient = builder.buildEventProcessorClient();
        processorClient.start();
        log.info("EventHubConsumerService started - eventHub={}, consumerGroup={}",
                properties.getEventHubName(), properties.getConsumerGroup());
    }

    @PreDestroy
    public void stop() {
        if (processorClient != null) {
            processorClient.stop();
        }
    }

    private CheckpointStore buildCheckpointStore() {
        BlobContainerAsyncClient containerClient;
        if ("managed-identity".equalsIgnoreCase(properties.getAuthMode())) {
            containerClient = new BlobContainerClientBuilder()
                    .endpoint(properties.getCheckpointStorageAccountUrl())
                    .containerName(properties.getCheckpointContainerName())
                    .credential(new DefaultAzureCredentialBuilder().build())
                    .buildAsyncClient();
        } else {
            containerClient = new BlobContainerClientBuilder()
                    .connectionString(properties.getCheckpointStorageConnectionString())
                    .containerName(properties.getCheckpointContainerName())
                    .buildAsyncClient();
        }
        return new BlobCheckpointStore(containerClient);
    }

    private void handleEvent(EventContext eventContext) {
        String body = eventContext.getEventData().getBodyAsString();
        try {
            // CUSTOMIZE: adjust this mapping to whatever JSON shape the actual
            // publisher (the "Relevant Events Source" in the flow diagram) sends.
            // At minimum it must include a stable, unique eventId.
            IncomingEventDto event = objectMapper.readValue(body, IncomingEventDto.class);

            notificationService.handleIncomingEvent(event);

            // Only checkpoint AFTER successful processing - see class javadoc.
            eventContext.updateCheckpoint();
        } catch (Exception e) {
            // Do NOT checkpoint on failure - this event will be redelivered on
            // restart/rebalance and retried. The idempotency guard in
            // NotificationService makes that safe.
            log.error("Failed to process Event Hub event, partition={}, sequenceNumber={}, body={}",
                    eventContext.getPartitionContext().getPartitionId(),
                    eventContext.getEventData().getSequenceNumber(),
                    body, e);
        }
    }

    private void handleError(ErrorContext errorContext) {
        log.error("EventHubConsumerService error on partition={}",
                errorContext.getPartitionContext().getPartitionId(), errorContext.getThrowable());
    }
}
