package gov.fdic.tip.notification.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Controls how the Event Hub consumer retries its OWN startup connection
 * when Event Hub / the checkpoint Blob Storage is unreachable at boot time.
 * This is separate from any message-processing retry logic - this is
 * specifically about not letting the app crash if Event Hub itself is
 * having a bad moment when this service starts.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "tip.event-hub.startup-retry")
public class EventHubStartupRetryProperties {

    /** How long to wait for the initial connection attempt before giving up on THAT attempt (not overall). */
    private long connectTimeoutMs = 15_000;

    /** Delay before the first retry after a failed startup attempt. */
    private long initialBackoffMs = 5_000;

    /** Backoff multiplier applied after each failed retry. */
    private double backoffMultiplier = 2.0;

    /** Retry delay is capped here, so we don't end up waiting an hour between attempts. */
    private long maxBackoffMs = 120_000;
}
