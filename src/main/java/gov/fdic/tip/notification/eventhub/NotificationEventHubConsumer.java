package gov.fdic.tip.notification.eventhub;

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
import gov.fdic.tip.notification.config.EventHubStartupRetryProperties;
import gov.fdic.tip.notification.dto.IncomingNotificationEvent;
import gov.fdic.tip.notification.service.NotificationDeliveryDispatchService;
import gov.fdic.tip.notification.service.NotificationIngestService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * FIX: this used to connect to Event Hub inside @PostConstruct, which meant
 * a connection failure (Event Hub down, Blob Storage unreachable, DNS/
 * network issue, auth misconfiguration - anything) threw an exception
 * during Spring context refresh and took the ENTIRE application down before
 * it ever came up. One flaky dependency meant zero actuator endpoints, zero
 * liveness, nothing - the whole pod failed to start.
 *
 * Now: implements SmartLifecycle instead of @PostConstruct. start() kicks
 * off connection attempts on a background thread and returns immediately -
 * Spring Boot finishes starting up regardless of whether Event Hub is
 * reachable yet. If the first attempt fails, it retries with exponential
 * backoff (see EventHubStartupRetryProperties) until it succeeds or the app
 * shuts down. Current connection status is exposed via isConnected() for
 * NotificationEventHubConsumerHealthIndicator to surface separately from
 * overall application health - see that class for why this matters for
 * Kubernetes liveness vs readiness.
 */
@Component
public class NotificationEventHubConsumer implements SmartLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(NotificationEventHubConsumer.class);

    private final NotificationEventHubProperties properties;
    private final EventHubStartupRetryProperties retryProperties;
    private final NotificationIngestService ingestService;
    private final NotificationDeliveryDispatchService dispatchService;
    private final ObjectMapper objectMapper;

    private final ScheduledExecutorService retryExecutor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "eventhub-consumer-startup-retry");
                t.setDaemon(true);
                return t;
            });

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private volatile EventProcessorClient processorClient;
    private volatile String lastFailureMessage;

    public NotificationEventHubConsumer(NotificationEventHubProperties properties,
                                         EventHubStartupRetryProperties retryProperties,
                                         NotificationIngestService ingestService,
                                         NotificationDeliveryDispatchService dispatchService,
                                         ObjectMapper objectMapper) {
        this.properties = properties;
        this.retryProperties = retryProperties;
        this.ingestService = ingestService;
        this.dispatchService = dispatchService;
        this.objectMapper = objectMapper;
    }

    // ---- SmartLifecycle ----
    // Spring calls start() during context startup but does NOT wait for it to
    // finish before considering the app "up" (unlike @PostConstruct, which
    // blocks refresh() and propagates exceptions into it). That's the crux
    // of the fix - see class javadoc.

    @Override
    public void start() {
        running.set(true);
        attemptConnectAsync(retryProperties.getInitialBackoffMs());
    }

    @Override
    public void stop() {
        running.set(false);
        retryExecutor.shutdownNow();
        if (processorClient != null) {
            processorClient.stop();
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    // ---- Connection logic ----

    private void attemptConnectAsync(long nextBackoffMs) {
        retryExecutor.execute(() -> {
            if (!running.get()) {
                return; // app is shutting down, don't keep retrying
            }
            try {
                doConnect();
                connected.set(true);
                lastFailureMessage = null;
                LOG.info("NotificationEventHubConsumer connected - eventHub={}, consumerGroup={}",
                        properties.getEventHubName(), properties.getConsumerGroup());
            } catch (Exception e) {
                connected.set(false);
                lastFailureMessage = e.getMessage();
                long capped = Math.min(nextBackoffMs, retryProperties.getMaxBackoffMs());
                LOG.error("NotificationEventHubConsumer failed to connect - will retry in {}ms. " +
                                "Application startup is NOT blocked by this - see NotificationEventHubConsumerHealthIndicator " +
                                "for current status. Cause: {}",
                        capped, e.getMessage(), e);

                long next = (long) (capped * retryProperties.getBackoffMultiplier());
                retryExecutor.schedule(() -> attemptConnectAsync(next), capped, TimeUnit.MILLISECONDS);
            }
        });
    }

    private void doConnect() {
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

        EventProcessorClient client = builder.buildEventProcessorClient();
        client.start();  // this is what was previously throwing straight into @PostConstruct
        this.processorClient = client;
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
            IncomingNotificationEvent event = objectMapper.readValue(body, IncomingNotificationEvent.class);
            ingestService.ingest(event);

            UUID eventId = event.eventId() != null ? UUID.fromString(event.eventId()) : null;
            if (eventId != null) {
                dispatchService.dispatchPendingDeliveries(eventId);
            }
            eventContext.updateCheckpoint();
        } catch (Exception e) {
            LOG.error("Failed to process notification event off Event Hub, partition={}, sequenceNumber={}, body={}",
                    eventContext.getPartitionContext().getPartitionId(),
                    eventContext.getEventData().getSequenceNumber(),
                    body, e);
        }
    }

    private void handleError(ErrorContext errorContext) {
        // A runtime error on an already-established connection (partition
        // rebalance issue, transient network blip mid-stream) - the Azure SDK
        // handles reconnection internally for these; we just log for visibility.
        // This is different from the startup connection failure this class's
        // retry logic handles - that's a separate, harder failure (can't even
        // establish the connection in the first place).
        LOG.error("NotificationEventHubConsumer runtime error on partition={}",
                errorContext.getPartitionContext().getPartitionId(), errorContext.getThrowable());
    }

    @PreDestroy
    public void cleanup() {
        stop();
    }

    // ---- Exposed for the health indicator ----

    public boolean isConnected() {
        return connected.get();
    }

    public String getLastFailureMessage() {
        return lastFailureMessage;
    }
}
