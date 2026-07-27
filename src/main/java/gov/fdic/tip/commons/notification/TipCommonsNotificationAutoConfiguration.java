package gov.fdic.tip.commons.notification;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Any TIP Spring Boot service that adds tip-commons-notification as a
 * dependency gets a NotificationPublishClient bean for free, with zero code
 * beyond application.yml properties:
 *
 * <pre>{@code
 * tip:
 * notification-publish:
 * enabled: true
 * auth-mode: connection-string
 * connection-string: Endpoint=sb://your-namespace.servicebus.windows.net/;SharedAccessKeyName=...
 * event-hub-name: notification-events
 * }</pre>
 *
 * Then anywhere in your service:
 *
 * <pre>{@code
 * @Autowired
 * private NotificationPublishClient notificationPublishClient;
 *
 * notificationPublishClient.publish(NotificationPublishRequest.builder()
 * .source("RETENTION_ETL")
 * .eventType("RETENTION_STAMPED")
 * .idempotencyKey("retention-stamp-" + record.getRecordId())   // stable if this step can retry
 * .recipientEmail(record.getOwnerEmail())
 * .context(Map.of("recordId", record.getRecordId()))
 * .build());
 * }</pre>
 *
 * Set tip.notification-publish.enabled=false to disable (e.g. most test profiles).
 */
@AutoConfiguration
@EnableConfigurationProperties(NotificationPublishProperties.class)
@ConditionalOnProperty(prefix = "tip.notification-publish", name = "enabled", havingValue = "true", matchIfMissing = true)
public class TipCommonsNotificationAutoConfiguration {

    /**
     * Creates and registers the central publishing engine bean.
     * The destroyMethod configuration ensures that Spring closes the underlying 
     * EventHubProducerClient connection pool cleanly when the application context is destroyed.
     */
    @Bean(destroyMethod = "close")
    public NotificationPublishEngine notificationPublishEngine(NotificationPublishProperties properties) {
        return NotificationPublishEngine.builder()
                .authMode(properties.getAuthMode())
                .connectionString(properties.getConnectionString())
                .eventHubName(properties.getEventHubName())
                .namespaceFullyQualifiedDomainName(properties.getNamespaceFullyQualifiedDomainName())
                .build();
    }

    @Bean
    public NotificationPublishClient notificationPublishClient(NotificationPublishEngine engine) {
        return new DefaultNotificationPublishClient(engine);
    }
}