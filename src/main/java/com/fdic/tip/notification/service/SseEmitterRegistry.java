package com.fdic.tip.notification.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Holds SseEmitters for users connected to THIS instance only.
 *
 * IMPORTANT: in a horizontally scaled deployment, a given user's emitter may
 * live on a different instance than the one that processes their event. That
 * gap is closed by NotificationPollingService, which polls the notification
 * table on an interval and lets each instance check its own local cache
 * here - see the sequence diagram, steps 5c/5d.
 */
@Slf4j
@Component
public class SseEmitterRegistry {

    private static final long EMITTER_TIMEOUT_MS = 30 * 60 * 1000L; // 30 min

    private final Map<String, List<SseEmitter>> emittersByUser = new ConcurrentHashMap<>();

    public SseEmitter register(String userId) {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        emittersByUser.computeIfAbsent(userId, k -> new CopyOnWriteArrayList<>()).add(emitter);

        emitter.onCompletion(() -> remove(userId, emitter));
        emitter.onTimeout(() -> remove(userId, emitter));
        emitter.onError(ex -> remove(userId, emitter));

        log.info("SSE emitter registered for userId={}, activeForUser={}", userId, emittersByUser.get(userId).size());
        return emitter;
    }

    private void remove(String userId, SseEmitter emitter) {
        List<SseEmitter> emitters = emittersByUser.get(userId);
        if (emitters != null) {
            emitters.remove(emitter);
            if (emitters.isEmpty()) {
                emittersByUser.remove(userId);
            }
        }
    }

    /** True if THIS instance holds at least one live emitter for the user. */
    public boolean hasLocalEmitter(String userId) {
        List<SseEmitter> emitters = emittersByUser.get(userId);
        return emitters != null && !emitters.isEmpty();
    }

    /** Users currently connected to THIS instance - used by NotificationPollingService to scope its query. */
    public java.util.Set<String> getConnectedUserIds() {
        return emittersByUser.keySet();
    }

    public void pushToUser(String userId, String eventName, Object data) {
        List<SseEmitter> emitters = emittersByUser.get(userId);
        if (emitters == null || emitters.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(data));
            } catch (IOException e) {
                log.warn("Failed to push SSE event to userId={}, dropping emitter", userId, e);
                remove(userId, emitter);
            }
        }
    }
}
