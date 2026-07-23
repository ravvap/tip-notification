package gov.fdic.tip.commons.notification;

import com.azure.core.credential.TokenCredential;
import com.azure.core.credential.TokenRequestContext;
import com.azure.identity.ClientSecretCredentialBuilder;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Pure Java, no Spring dependency - calls POST {baseUrl}/api/v1/notification-events
 * on the notification service. Mirrors RetentionEngine's layering: this is
 * the thing that actually does work; NotificationPublishService (Spring
 * wrapper) and NotificationPublishUtil (static API) both delegate to an
 * instance of this.
 *
 * Auth: acquires a bearer token as THIS calling service's own Entra service
 * principal (application permissions / client credentials flow - not a
 * signed-in user's token, since this typically runs from a batch job or
 * backend service with no user session). The notification service reads
 * this token's claims to populate publisherPrincipal server-side.
 */
public class NotificationPublishEngine {

    private final String baseUrl;
    private final TokenCredential credential;
    private final String tokenScope;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Duration requestTimeout;

    private NotificationPublishEngine(Builder b) {
        this.baseUrl = stripTrailingSlash(b.baseUrl);
        this.tokenScope = b.tokenScope;
        this.requestTimeout = b.requestTimeout;
        this.credential = "client-secret".equalsIgnoreCase(b.authMode)
                ? new ClientSecretCredentialBuilder()
                        .tenantId(b.tenantId)
                        .clientId(b.clientId)
                        .clientSecret(b.clientSecret)
                        .build()
                : new DefaultAzureCredentialBuilder().build();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(b.connectTimeoutMs))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    public static Builder builder() {
        return new Builder();
    }

    public NotificationPublishResponse publish(NotificationPublishRequest request) throws NotificationPublishException {
        try {
            String token = credential.getToken(new TokenRequestContext().addScopes(tokenScope))
                    .block()
                    .getToken();

            String json = objectMapper.writeValueAsString(toWireFormat(request));

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/v1/notification-events"))
                    .timeout(requestTimeout)
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200 || response.statusCode() == 201) {
                return parseResponse(response.body());
            }
            if (response.statusCode() == 409) {
                // CONFLICT with the same idempotencyKey but a DIFFERENT payload -
                // this is a caller bug (reusing a key for a genuinely different
                // event), not a transient failure - surfaced distinctly so it
                // doesn't get silently retried like a network error would.
                throw new NotificationPublishException(
                        "Idempotency key reused with a different payload (409): " + response.body());
            }
            throw new NotificationPublishException(
                    "Notification publish failed, status=" + response.statusCode() + ", body=" + response.body());

        } catch (NotificationPublishException e) {
            throw e;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new NotificationPublishException(
                    "Failed to call notification service for source=" + request.getSource()
                            + ", eventType=" + request.getEventType(), e);
        } catch (Exception e) {
            throw new NotificationPublishException("Unexpected error publishing notification event", e);
        }
    }

    private NotificationPublishResponse parseResponse(String body) throws IOException {
        JsonNode node = objectMapper.readTree(body);
        UUID id = UUID.fromString(node.get("notificationEventId").asText());
        Instant createdAt = Instant.parse(node.get("createdAt").asText());
        boolean duplicate = node.hasNonNull("duplicate") && node.get("duplicate").asBoolean();
        return new NotificationPublishResponse(id, createdAt, duplicate);
    }

    // Field names MUST match gov.fdic.tip.service.dto.PublishNotificationEventDTO exactly.
    private Map<String, Object> toWireFormat(NotificationPublishRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("eventId", request.getEventId());
        body.put("source", request.getSource());
        body.put("eventType", request.getEventType());
        body.put("idempotencyKey", request.getIdempotencyKey());
        body.put("severity", request.getSeverity());
        body.put("recipientEmail", request.getRecipientEmail());
        body.put("recipientRole", request.getRecipientRole());
        body.put("context", request.getContext());
        body.put("correlationId", request.getCorrelationId());
        return body;
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    public static final class Builder {
        private String baseUrl;
        private String authMode = "managed-identity"; // managed-identity | client-secret
        private String tenantId;
        private String clientId;
        private String clientSecret;
        private String tokenScope; // e.g. api://tip-notification-service/.default
        private long connectTimeoutMs = 3000;
        private Duration requestTimeout = Duration.ofSeconds(5);

        public Builder baseUrl(String baseUrl) { this.baseUrl = baseUrl; return this; }
        public Builder authMode(String authMode) { this.authMode = authMode; return this; }
        public Builder tenantId(String tenantId) { this.tenantId = tenantId; return this; }
        public Builder clientId(String clientId) { this.clientId = clientId; return this; }
        public Builder clientSecret(String clientSecret) { this.clientSecret = clientSecret; return this; }
        public Builder tokenScope(String tokenScope) { this.tokenScope = tokenScope; return this; }
        public Builder connectTimeoutMs(long ms) { this.connectTimeoutMs = ms; return this; }
        public Builder requestTimeout(Duration timeout) { this.requestTimeout = timeout; return this; }

        public NotificationPublishEngine build() {
            return new NotificationPublishEngine(this);
        }
    }
}
