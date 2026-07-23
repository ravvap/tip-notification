package gov.fdic.tip.eventhub;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "tip.event-hub")
public class NotificationEventHubProperties {

    private String authMode = "managed-identity"; // connection-string | managed-identity
    private String connectionString;
    private String fullyQualifiedNamespace;
    private String eventHubName;
    private String consumerGroup = "$Default";

    private String checkpointStorageConnectionString;
    private String checkpointContainerName = "tip-notification-checkpoints";
    private String checkpointStorageAccountUrl;
}
