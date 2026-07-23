package gov.fdic.tip.eventhub;

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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import gov.fdic.tip.service.NotificationDeliveryDispatchService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Consumes the event published by NotificationEventResource /
 * NotificationPublishService.publish() once the NotificationEvent and its
 * NotificationDelivery rows have already been persisted (PENDING for EMAIL/
 * other channels, DELIVERED for IN_APP). This consumer's only job is to
 * trigger channel dispatch for the still-PENDING deliveries - it does not
 * re-derive or re-persist the event/delivery rows, since NotificationPublishService
 * already did that synchronously in the REST call.
 *
 * Expected message payload (published by NotificationPublishService after
 * eventRepository.save(...) commits): {"notificationEventId": "<uuid>"}.
 * Deliberately minimal - the consumer re-reads current state from the DB
 * rather than trusting a payload that could be stale by the time it's
 * processed, matching how NotificationDeliveryDispatchService re-checks
 * PENDING status right before dispatching each delivery.
 *
 * Checkpointing: only advanced AFTER dispatchPendingDeliveries() returns
 * without throwing. If it throws, this event is redelivered on
 * restart/rebalance - safe because reprocessing only re-touches rows that
 * are still PENDING (see NotificationDeliveryDispatchService javadoc).
 */
@Component
public class NotificationEventHubConsumer {

    private static final Logger LOG = LoggerFactory.getLogger(NotificationEventHubConsumer.class);

    private final NotificationEventHubProperties properties;
    private final NotificationDeliveryDispatchService dispatchService;
    private final ObjectMapper objectMapper;

    private EventProcessorClient processorClient;

    public NotificationEventHubConsumer(NotificationEventHubProperties properties,
                                         NotificationDeliveryDispatchService dispatchService,
                                         ObjectMapper objectMapper) {
        this.properties = properties;
        this.dispatchService = dispatchService;
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
        LOG.info("NotificationEventHubConsumer started - eventHub={}, consumerGroup={}",
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
            JsonNode json = objectMapper.readTree(body);
            UUID notificationEventId = UUID.fromString(json.get("notificationEventId").asText());

            dispatchService.dispatchPendingDeliveries(notificationEventId);

            eventContext.updateCheckpoint();
        } catch (Exception e) {
            // No checkpoint on failure - redelivered and retried; safe per class javadoc.
            LOG.error("Failed to process notification event off Event Hub, partition={}, sequenceNumber={}, body={}",
                    eventContext.getPartitionContext().getPartitionId(),
                    eventContext.getEventData().getSequenceNumber(),
                    body, e);
        }
    }

    private void handleError(ErrorContext errorContext) {
        LOG.error("NotificationEventHubConsumer error on partition={}",
                errorContext.getPartitionContext().getPartitionId(), errorContext.getThrowable());
    }
}
