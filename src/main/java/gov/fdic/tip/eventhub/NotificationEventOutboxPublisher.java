package gov.fdic.tip.eventhub;

import com.azure.core.credential.TokenCredential;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.messaging.eventhubs.EventData;
import com.azure.messaging.eventhubs.EventDataBatch;
import com.azure.messaging.eventhubs.EventHubClientBuilder;
import com.azure.messaging.eventhubs.EventHubProducerClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import gov.fdic.tip.service.NotificationEventPersistedEvent;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Publishes to Event Hub ONLY after NotificationPublishService's transaction
 * has committed - see NotificationEventPersistedEvent's javadoc for why. If
 * the transaction rolls back, this listener never fires at all (that's the
 * whole point of AFTER_COMMIT), so Event Hub never hears about a
 * NotificationEvent that doesn't durably exist.
 *
 * FAILURE MODE TO KNOW: if this publish call itself fails (Event Hub
 * unreachable, throttled, etc.), the NotificationEvent + its PENDING
 * deliveries are ALREADY durably persisted in Postgres by this point - they
 * just won't get an immediate dispatch trigger. This is NOT a silent data
 * loss case: PendingDeliveryRetrySweep (in tip-notification-consumer)
 * independently polls for PENDING deliveries every 60s regardless of how
 * they got into that state, so a failed publish here is self-healing within
 * that window, not stuck forever. Still logged as an error so it's visible
 * to monitoring rather than only relying on the sweep silently catching it.
 */
@Component
public class NotificationEventOutboxPublisher {

    private static final Logger LOG = LoggerFactory.getLogger(NotificationEventOutboxPublisher.class);

    private final NotificationEventHubProperties properties;
    private final ObjectMapper objectMapper;
    private EventHubProducerClient producerClient;

    public NotificationEventOutboxPublisher(NotificationEventHubProperties properties, ObjectMapper objectMapper) {
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
        LOG.info("NotificationEventOutboxPublisher ready - eventHub={}", properties.getEventHubName());
    }

    @PreDestroy
    public void shutdown() {
        if (producerClient != null) {
            producerClient.close();
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onNotificationEventPersisted(NotificationEventPersistedEvent event) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("notificationEventId", event.notificationEventId().toString());
            String json = objectMapper.writeValueAsString(payload);

            EventDataBatch batch = producerClient.createBatch();
            batch.tryAdd(new EventData(json));
            producerClient.send(batch);

            LOG.info("Published NotificationEventPersistedEvent to Event Hub, notificationEventId={}",
                    event.notificationEventId());
        } catch (Exception e) {
            // Deliberately NOT rethrown - the transaction already committed, throwing
            // here has nothing to roll back and would just be an unhandled exception
            // on a listener thread. See class javadoc: PendingDeliveryRetrySweep is
            // the actual safety net for this failure mode.
            LOG.error("Failed to publish to Event Hub for notificationEventId={} - deliveries remain PENDING, " +
                            "PendingDeliveryRetrySweep will pick them up on its next pass",
                    event.notificationEventId(), e);
        }
    }
}
