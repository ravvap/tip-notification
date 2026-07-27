package gov.fdic.tip.commons.notification;

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
        // Prevent instantiation of utility class
    }

    /**
     * Configure the engine using an explicit Azure Event Hub Connection String.
     * Maps to the 'connection-string' authentication strategy.
     *
     * @param connectionString The full connection string pointing to the Azure Event Hubs namespace.
     * @param eventHubName     The target name of the specific Event Hub entity instance.
     */
    public static void configure(String connectionString, String eventHubName) {
        engine = NotificationPublishEngine.builder()
                .authMode("connection-string")
                .connectionString(connectionString)
                .eventHubName(eventHubName)
                .build();
    }

    /**
     * Configure the engine using Azure Active Directory / Entra ID Managed Identity.
     * Maps to the 'managed-identity' authentication strategy.
     *
     * @param namespaceFullyQualifiedDomainName The fully qualified domain name (e.g., "yournamespace.servicebus.windows.net").
     * @param eventHubName                      The target name of the specific Event Hub entity instance.
     */
    public static void configureWithManagedIdentity(String namespaceFullyQualifiedDomainName, String eventHubName) {
        engine = NotificationPublishEngine.builder()
                .authMode("managed-identity")
                .namespaceFullyQualifiedDomainName(namespaceFullyQualifiedDomainName)
                .eventHubName(eventHubName)
                .build();
    }

    /**
     * Proxies the request down to the initialized, underlying Event Hub producer.
     *
     * @param request The data payload containing message body, context, and routing rules.
     * @return A confirmation response with unique transmission metadata.
     * @throws NotificationPublishException If called before configuration or if the Event Hub client delivery fails.
     */
    public static NotificationPublishResponse publish(NotificationPublishRequest request) throws NotificationPublishException {
        if (engine == null) {
            throw new IllegalStateException(
                    "NotificationPublishUtil.configure(...) must be called once at startup before publish(...)");
        }
        return engine.publish(request);
    }
}