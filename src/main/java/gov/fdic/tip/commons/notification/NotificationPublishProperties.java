package gov.fdic.tip.commons.notification;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Bind under `tip.notification-publish.*` in the CALLING service's application.yml. */
@Getter
@Setter
@ConfigurationProperties(prefix = "tip.notification-publish")
public class NotificationPublishProperties {

    /** Set to false to disable the publish bean entirely (e.g. most unit test profiles). */
    private boolean enabled = true;

    /** Base URL of the notification service, e.g. https://tip-notification.internal.fdic.gov */
    private String baseUrl;

    private String authMode = "managed-identity"; // managed-identity | client-secret
    private String tenantId;
    private String clientId;
    private String clientSecret;

    /** Entra App ID URI scope for the notification service's API, e.g. api://tip-notification-service/.default */
    private String tokenScope;

    private long connectTimeoutMs = 3000;
    private long requestTimeoutMs = 5000;
}
