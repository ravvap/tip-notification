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
 *   tip:
 *     notification-publish:
 *       base-url: https://tip-notification.internal.fdic.gov
 *       auth-mode: managed-identity
 *       token-scope: api://tip-notification-service/.default
 *
 * Then anywhere in your service:
 *
 *   @Autowired
 *   private NotificationPublishClient notificationPublishClient;
 *
 *   notificationPublishClient.publish(NotificationPublishRequest.builder()
 *       .source("RETENTION_ETL")
 *       .eventType("RETENTION_STAMPED")
 *       .idempotencyKey("retention-stamp-" + record.getRecordId())   // stable if this step can retry
 *       .recipientEmail(record.getOwnerEmail())
 *       .context(Map.of("recordId", record.getRecordId()))
 *       .build());
 *
 * Set tip.notification-publish.enabled=false to disable (e.g. most test profiles).
 */
@AutoConfiguration
@EnableConfigurationProperties(NotificationPublishProperties.class)
@ConditionalOnProperty(prefix = "tip.notification-publish", name = "enabled", havingValue = "true", matchIfMissing = true)
public class TipCommonsNotificationAutoConfiguration {

    @Bean
    public NotificationPublishEngine notificationPublishEngine(NotificationPublishProperties properties) {
        return NotificationPublishEngine.builder()
                .baseUrl(properties.getBaseUrl())
                .authMode(properties.getAuthMode())
                .tenantId(properties.getTenantId())
                .clientId(properties.getClientId())
                .clientSecret(properties.getClientSecret())
                .tokenScope(properties.getTokenScope())
                .connectTimeoutMs(properties.getConnectTimeoutMs())
                .requestTimeout(java.time.Duration.ofMillis(properties.getRequestTimeoutMs()))
                .build();
    }

    @Bean
    public NotificationPublishClient notificationPublishClient(NotificationPublishEngine engine) {
        return new DefaultNotificationPublishClient(engine);
    }
}
