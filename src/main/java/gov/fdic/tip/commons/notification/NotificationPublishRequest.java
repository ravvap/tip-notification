package gov.fdic.tip.commons.notification;

import java.util.Map;
import java.util.Objects;

/**
 * Mirrors the actual fields on gov.fdic.tip.service.dto.PublishNotificationEventDTO
 * (eventId, source, eventType, idempotencyKey, severity, recipientEmail,
 * recipientRole, context, correlationId, publisherPrincipal). Deliberately
 * omits publisherPrincipal as a builder option - even though the DTO has a
 * setter for it, the server populates it from the caller's authenticated
 * identity (see NotificationEventResource), so this library doesn't let
 * callers set it and risk it being silently overridden or, worse, trusted
 * from client input.
 *
 * idempotencyKey is the field NotificationPublishService's
 * findBySourceAndIdempotencyKey(...) dedup check depends on. If your
 * operation might retry (a batch step rerunning, a request timeout causing
 * a client-side retry, etc.), you MUST supply a STABLE key derived from your
 * source record/operation - otherwise a retry creates a second
 * NotificationEvent instead of being recognized as the same one.
 */
public final class NotificationPublishRequest {

    private final String eventId;            // optional - server generates one if omitted
    private final String source;
    private final String eventType;
    private final String severity;           // matches NotificationSeverity enum name, e.g. "HIGH" - null lets server use config default
    private final String correlationId;
    private final String idempotencyKey;
    private final Map<String, Object> context;
    private final String recipientEmail;     // omit for a broadcast to the event's configured audience (see resolveRecipients/broadcastCap server-side)
    private final String recipientRole;

    private NotificationPublishRequest(Builder b) {
        this.source = Objects.requireNonNull(b.source, "source is required");
        this.eventType = Objects.requireNonNull(b.eventType, "eventType is required");
        this.idempotencyKey = Objects.requireNonNull(b.idempotencyKey,
                "idempotencyKey is required - see class javadoc on why a stable key matters for retryable callers");
        this.eventId = b.eventId;
        this.severity = b.severity;
        this.correlationId = b.correlationId;
        this.context = b.context;
        this.recipientEmail = b.recipientEmail;
        this.recipientRole = b.recipientRole;
    }

    public String getEventId() { return eventId; }
    public String getSource() { return source; }
    public String getEventType() { return eventType; }
    public String getSeverity() { return severity; }
    public String getCorrelationId() { return correlationId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public Map<String, Object> getContext() { return context; }
    public String getRecipientEmail() { return recipientEmail; }
    public String getRecipientRole() { return recipientRole; }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String eventId;
        private String source;
        private String eventType;
        private String severity;
        private String correlationId;
        private String idempotencyKey;
        private Map<String, Object> context;
        private String recipientEmail;
        private String recipientRole;

        /** Optional - server generates a UUID if omitted. */
        public Builder eventId(String eventId) { this.eventId = eventId; return this; }
        public Builder source(String source) { this.source = source; return this; }
        public Builder eventType(String eventType) { this.eventType = eventType; return this; }
        public Builder severity(String severity) { this.severity = severity; return this; }
        public Builder correlationId(String correlationId) { this.correlationId = correlationId; return this; }

        /** REQUIRED. See class javadoc - must be stable across retries of the same logical operation. */
        public Builder idempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; return this; }

        public Builder context(Map<String, Object> context) { this.context = context; return this; }
        public Builder recipientEmail(String recipientEmail) { this.recipientEmail = recipientEmail; return this; }
        public Builder recipientRole(String recipientRole) { this.recipientRole = recipientRole; return this; }

        public NotificationPublishRequest build() {
            return new NotificationPublishRequest(this);
        }
    }
}
