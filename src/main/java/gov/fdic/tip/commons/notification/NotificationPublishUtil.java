package gov.fdic.tip.commons.notification;

import java.time.Duration;

/**
 * Static entry point for code that isn't Spring-managed (matches
 * RetentionUtil's pattern in tip-commons). Must be initialized once at
 * startup via configure(...) before use - typically from a
 * non-Spring-managed batch bootstrap class.
 *
 * Spring Boot consumers should prefer @Autowiring NotificationPublishClient
 * instead - this class exists specifically for code paths that don't have
 * Spring DI available.
 */
public final class NotificationPublishUtil {

    private static volatile NotificationPublishEngine engine;

    private NotificationPublishUtil() {
    }

    public static void configure(String baseUrl, String tenantId, String clientId, String clientSecret, String tokenScope) {
        engine = NotificationPublishEngine.builder()
                .baseUrl(baseUrl)
                .authMode("client-secret")
                .tenantId(tenantId)
                .clientId(clientId)
                .clientSecret(clientSecret)
                .tokenScope(tokenScope)
                .requestTimeout(Duration.ofSeconds(5))
                .build();
    }

    public static void configureWithManagedIdentity(String baseUrl, String tokenScope) {
        engine = NotificationPublishEngine.builder()
                .baseUrl(baseUrl)
                .authMode("managed-identity")
                .tokenScope(tokenScope)
                .requestTimeout(Duration.ofSeconds(5))
                .build();
    }

    public static NotificationPublishResponse publish(NotificationPublishRequest request) throws NotificationPublishException {
        if (engine == null) {
            throw new IllegalStateException(
                    "NotificationPublishUtil.configure(...) must be called once at startup before publish(...)");
        }
        return engine.publish(request);
    }
}
