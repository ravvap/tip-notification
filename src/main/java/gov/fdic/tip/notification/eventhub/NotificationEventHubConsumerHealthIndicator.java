package gov.fdic.tip.notification.eventhub;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Exposes Event Hub connection status at /actuator/health (component
 * "eventHubConsumer") WITHOUT that status being able to crash the app - see
 * NotificationEventHubConsumer's javadoc for the startup fix this pairs
 * with.
 *
 * IMPORTANT: by default Spring Boot aggregates ALL HealthIndicators into
 * the top-level /actuator/health status, which would make the app report
 * globally DOWN whenever Event Hub is unreachable - not much better than
 * crashing, if that status drives a Kubernetes liveness probe that restarts
 * the pod. Fix that at the deployment level, not in code: configure this
 * indicator into the READINESS group only, not liveness -
 *
 *   management:
 *     endpoint:
 *       health:
 *         group:
 *           readiness:
 *             include: eventHubConsumer, db
 *           liveness:
 *             include: livenessState   # deliberately does NOT include eventHubConsumer
 *
 * Then point Kubernetes at /actuator/health/liveness for restart decisions
 * (so a flaky Event Hub connection never causes a restart loop) and
 * /actuator/health/readiness for traffic/alerting decisions (so it's still
 * visible and can page someone, just without killing the pod).
 */
@Component("eventHubConsumer")
public class NotificationEventHubConsumerHealthIndicator implements HealthIndicator {

    private final NotificationEventHubConsumer consumer;

    public NotificationEventHubConsumerHealthIndicator(NotificationEventHubConsumer consumer) {
        this.consumer = consumer;
    }

    @Override
    public Health health() {
        if (consumer.isConnected()) {
            return Health.up().build();
        }
        Health.Builder builder = Health.down();
        if (consumer.getLastFailureMessage() != null) {
            builder.withDetail("lastFailure", consumer.getLastFailureMessage());
        }
        return builder.build();
    }
}
