package com.fdic.tip.notification.service;

import com.fdic.tip.notification.entity.NotificationEntity;
import com.fdic.tip.notification.repository.NotificationRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Fan-out mechanism for live SSE push, using plain DB polling - no pg_notify,
 * no LISTEN, no DB triggers/functions, no Redis or Service Bus.
 *
 * Each instance runs this independently. Every tick, it asks Postgres:
 * "any new notifications, since my last check, for users currently
 * connected to ME?" and pushes matches to their local SseEmitter.
 *
 * Trade-off vs pg_notify: delivery latency is bounded by POLL_INTERVAL_MS
 * rather than being instant. For a bell-icon/toast use case that's generally
 * an acceptable trade for removing the extra connection-lifecycle complexity
 * that LISTEN/NOTIFY required (dedicated non-pooled connection, reconnect
 * loop, health checks). Tune the interval down if 2s is too slow, keeping in
 * mind DB load scales with (interval frequency x instance count).
 */
@Slf4j
@Component
public class NotificationPollingService {

    private static final long POLL_INTERVAL_MS = 2000L;

    private final NotificationRepository notificationRepository;
    private final SseEmitterRegistry emitterRegistry;

    // Per-instance watermark - only needs to track what THIS instance has already
    // delivered locally, not a durable/shared cursor. Postgres remains the source
    // of truth; a missed tick just means the next tick catches up.
    private final AtomicReference<OffsetDateTime> lastPolledAt = new AtomicReference<>(OffsetDateTime.now());

    public NotificationPollingService(NotificationRepository notificationRepository,
                                       SseEmitterRegistry emitterRegistry) {
        this.notificationRepository = notificationRepository;
        this.emitterRegistry = emitterRegistry;
    }

    @Scheduled(fixedDelay = POLL_INTERVAL_MS)
    public void poll() {
        Set<String> connectedUserIds = emitterRegistry.getConnectedUserIds();
        if (connectedUserIds.isEmpty()) {
            return; // nothing to do - no local connections on this instance right now
        }

        OffsetDateTime since = lastPolledAt.get();
        OffsetDateTime tickStartedAt = OffsetDateTime.now();

        try {
            List<NotificationEntity> newOnes =
                    notificationRepository.findByUserIdInAndCreatedAtAfter(connectedUserIds, since);

            for (NotificationEntity n : newOnes) {
                emitterRegistry.pushToUser(n.getUserId(), "notification", Map.of(
                        "notificationId", n.getId().toString(),
                        "userId", n.getUserId()));
                log.debug("Pushed notification {} to userId={} via polling fan-out", n.getId(), n.getUserId());
            }

            lastPolledAt.set(tickStartedAt);
        } catch (Exception e) {
            // Don't advance the watermark on failure - next tick will retry the same window.
            log.error("NotificationPollingService: poll tick failed, will retry next interval", e);
        }
    }
}
