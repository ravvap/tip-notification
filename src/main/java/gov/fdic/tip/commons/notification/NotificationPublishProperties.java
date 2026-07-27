package gov.fdic.tip.commons.notification;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bind under `tip.notification-publish.*` in the CALLING service's application.yml.
 * Configures the microservice to communicate directly with Azure Event Hubs.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "tip.notification-publish")
public class NotificationPublishProperties {

    /**
     * Set to false to disable the publish bean entirely (e.g., for most unit test profiles).
     * Defaults to true.
     */
    private boolean enabled = true;

    /**
     * Authentication strategy used to connect to Azure Event Hub.
     * Supported values: "managed-identity" or "connection-string".
     * Defaults to "managed-identity".
     */
    private String authMode = "managed-identity";

    /**
     * Required if authMode is "connection-string".
     * The full SAS connection string pointing to your Azure Event Hubs namespace.
     * E.g., "Endpoint=sb://your-namespace.servicebus.windows.net/;SharedAccessKeyName=...;SharedAccessKey=..."
     */
    private String connectionString;

    /**
     * The target name of the specific Event Hub entity instance within the namespace.
     * Optional for connection strings if the entity path is already embedded inside the 
     * connection string itself, but recommended for clarity. Required for managed-identity.
     */
    private String eventHubName;

    /**
     * Required if authMode is "managed-identity".
     * The fully qualified domain name of the Azure Event Hubs namespace.
     * E.g., "your-namespace.servicebus.windows.net"
     */
    private String namespaceFullyQualifiedDomainName;
}