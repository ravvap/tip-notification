package com.fdic.tip.notification.eventhub;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bind under `tip.event-hub.*`. Two auth modes are supported, matching how
 * TIP already authenticates to Azure elsewhere (Key Vault, ACS):
 *   - "connection-string": simplest for local/dev, uses a shared access key
 *   - "managed-identity": recommended for deployed environments - no secret
 *     to rotate, uses DefaultAzureCredential (system/user-assigned identity)
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "tip.event-hub")
public class EventHubProperties {

    private String authMode = "managed-identity"; // connection-string | managed-identity

    /** Only used when authMode = connection-string. */
    private String connectionString;

    /** Only used when authMode = managed-identity, e.g. tip-eventhub-ns.servicebus.windows.net */
    private String fullyQualifiedNamespace;

    private String eventHubName;
    private String consumerGroup = "$Default";

    // --- Checkpoint store (Azure Blob Storage) ---
    private String checkpointStorageConnectionString;
    private String checkpointContainerName = "tip-notification-checkpoints";
    private String checkpointStorageAccountUrl; // used with managed identity instead of a connection string
}
