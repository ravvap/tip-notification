package gov.fdic.tip.commons.notification;

import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.messaging.eventhubs.EventData;
import com.azure.messaging.eventhubs.EventDataBatch;
import com.azure.messaging.eventhubs.EventHubClientBuilder;
import com.azure.messaging.eventhubs.EventHubProducerClient;
import com.azure.messaging.eventhubs.models.CreateBatchOptions;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;

/**
 * Pure Java engine responsible for direct publishing to an Azure Event Hub instance.
 * Eliminates the external notification-service dependency, enabling standalone batch jobs
 * and Spring Boot services to publish safely and concurrently.
 * * Includes built-in retry mechanics through the Azure Event Hubs client SDK.
 */
public class NotificationPublishEngine {

    private final EventHubProducerClient producerClient;
    private final ObjectMapper objectMapper;

    private NotificationPublishEngine(Builder builder) {
        this.objectMapper = new ObjectMapper();
        
        EventHubClientBuilder clientBuilder = new EventHubClientBuilder();

        if ("managed-identity".equalsIgnoreCase(builder.authMode)) {
            if (builder.namespaceFullyQualifiedDomainName == null || builder.namespaceFullyQualifiedDomainName.isBlank()) {
                throw new IllegalArgumentException("namespaceFullyQualifiedDomainName is required for managed-identity auth mode.");
            }
            if (builder.eventHubName == null || builder.eventHubName.isBlank()) {
                throw new IllegalArgumentException("eventHubName is required.");
            }
            clientBuilder.credential(
                    builder.namespaceFullyQualifiedDomainName,
                    builder.eventHubName,
                    new DefaultAzureCredentialBuilder().build()
            );
        } else if ("connection-string".equalsIgnoreCase(builder.authMode)) {
            if (builder.connectionString == null || builder.connectionString.isBlank()) {
                throw new IllegalArgumentException("connectionString is required for connection-string auth mode.");
            }
            if (builder.eventHubName == null || builder.eventHubName.isBlank()) {
                clientBuilder.connectionString(builder.connectionString);
            } else {
                clientBuilder.connectionString(builder.connectionString, builder.eventHubName);
            }
        } else {
            throw new IllegalArgumentException("Unsupported authMode: " + builder.authMode + ". Must be 'managed-identity' or 'connection-string'.");
        }

        this.producerClient = clientBuilder.buildProducerClient();
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Publishes a notification event request directly into Azure Event Hub.
     * * @param request the notification event information.
     * @return a verification response mirroring a successful message push.
     * @throws NotificationPublishException if serialization fails or Azure rejects the event.
     */
    public NotificationPublishResponse publish(NotificationPublishRequest request) throws NotificationPublishException {
        if (request == null) {
            throw new IllegalArgumentException("NotificationPublishRequest cannot be null");
        }

        try {
            // 1. Convert the object request payload to a structured JSON string
            String jsonPayload = objectMapper.writeValueAsString(request);
            EventData eventData = new EventData(jsonPayload);

            // 2. Attach metadata properties to the event wrapper if helpful for downstream consumers
            if (request.getSource() != null) {
                eventData.getProperties().put("source", request.getSource());
            }
            if (request.getEventType() != null) {
                eventData.getProperties().put("eventType", request.getEventType());
            }

            // 3. Leverage the Idempotency Key as the Partition Key so that retries or updates for
            // the same event land orderly inside the exact same Event Hub partition.
            CreateBatchOptions options = new CreateBatchOptions();
            if (request.getIdempotencyKey() != null && !request.getIdempotencyKey().isBlank()) {
                options.setPartitionKey(request.getIdempotencyKey());
            }

            // 4. Create batch and publish
            EventDataBatch batch = producerClient.createBatch(options);
            if (!batch.tryAdd(eventData)) {
                throw new NotificationPublishException("Notification event size exceeds the maximum allowed Event Hub block payload.");
            }

            // Synchronous delivery to ensure we catch exceptions immediately if things go wrong
            producerClient.send(batch);

            // Generate a local transaction confirmation matching the old DTO signature
            UUID trackingId = request.getEventId() != null ? UUID.fromString(request.getEventId()) : UUID.randomUUID();
            return new NotificationPublishResponse(
                    trackingId,
                    Instant.now(),
                    false // duplicate tracking is handled consumer-side now
            );

        } catch (Exception e) {
            throw new NotificationPublishException("Failed to directly stream message payload into Event Hub.", e);
        }
    }

    /**
     * Closes the underlying Event Hub client connection pool when the environment shuts down.
     */
    public void close() {
        if (producerClient != null) {
            producerClient.close();
        }
    }

    /**
     * Fluent API Builder constructor pattern mapping both plain Java and Spring Boot configurations.
     */
    public static final class Builder {
        private String authMode = "managed-identity"; // defaults to managed-identity
        private String connectionString;
        private String eventHubName;
        private String namespaceFullyQualifiedDomainName;

        public Builder authMode(String authMode) {
            this.authMode = authMode;
            return this;
        }

        public Builder connectionString(String connectionString) {
            this.connectionString = connectionString;
            return this;
        }

        public Builder eventHubName(String eventHubName) {
            this.eventHubName = eventHubName;
            return this;
        }

        public Builder namespaceFullyQualifiedDomainName(String namespaceFullyQualifiedDomainName) {
            this.namespaceFullyQualifiedDomainName = namespaceFullyQualifiedDomainName;
            return this;
        }

        public NotificationPublishEngine build() {
            return new NotificationPublishEngine(this);
        }
    }
}