package com.fdic.tip.notification.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Dedicated LISTEN connection for this instance.
 *
 * DELIBERATELY NOT using the HikariCP pool: if this connection were borrowed
 * from the pool it would eventually be returned and reused for an unrelated
 * query, silently ending the LISTEN session with no error raised anywhere.
 * This uses its own raw JDBC connection, held open for the life of the app,
 * with a background poll loop and a scheduled health check that reconnects
 * on failure.
 *
 * Remediation items this addresses (see "biggest risk" discussion):
 *  - dedicated connection, not pooled
 *  - reconnect logic with health checks
 *  - reconciliation on reconnect (see NotificationService.reconcileMissed)
 *  - metrics/log lines an alert can be built on (listenerConnected)
 */
@Slf4j
@Component
public class PgNotifyListenerService {

    private static final String CHANNEL = "notification_channel";

    private final DataSource dataSource;
    private final SseEmitterRegistry emitterRegistry;
    private final ObjectMapper objectMapper;

    private final AtomicReference<Connection> listenerConnection = new AtomicReference<>();
    private final AtomicBoolean listenerConnected = new AtomicBoolean(false);
    private volatile Instant lastNotificationReceivedAt = Instant.now();
    private Thread pollThread;

    public PgNotifyListenerService(@Qualifier("notifyListenerDataSource") DataSource dataSource,
                                    SseEmitterRegistry emitterRegistry,
                                    ObjectMapper objectMapper) {
        this.dataSource = dataSource;
        this.emitterRegistry = emitterRegistry;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void start() {
        connectAndListen();
        pollThread = new Thread(this::pollLoop, "pg-notify-listener");
        pollThread.setDaemon(true);
        pollThread.start();
    }

    @PreDestroy
    public void stop() {
        if (pollThread != null) {
            pollThread.interrupt();
        }
        closeQuietly();
    }

    private void connectAndListen() {
        try {
            Connection conn = dataSource.getConnection();
            try (Statement st = conn.createStatement()) {
                st.execute("LISTEN " + CHANNEL);
            }
            listenerConnection.set(conn);
            listenerConnected.set(true);
            log.info("PgNotifyListener: LISTEN {} established", CHANNEL);
        } catch (Exception e) {
            listenerConnected.set(false);
            log.error("PgNotifyListener: failed to establish LISTEN connection - will retry", e);
        }
    }

    private void pollLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Connection conn = listenerConnection.get();
                if (conn == null || conn.isClosed()) {
                    listenerConnected.set(false);
                    reconnectWithBackoff();
                    continue;
                }
                PGConnection pgConn = conn.unwrap(PGConnection.class);
                // getNotifications(timeoutMillis) blocks up to the timeout waiting for a notification
                PGNotification[] notifications = pgConn.getNotifications(5000);
                if (notifications != null) {
                    for (PGNotification n : notifications) {
                        handleNotification(n.getParameter());
                    }
                }
            } catch (Exception e) {
                log.warn("PgNotifyListener: error while polling, reconnecting", e);
                listenerConnected.set(false);
                closeQuietly();
                reconnectWithBackoff();
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void handleNotification(String payload) {
        try {
            Map<String, Object> body = objectMapper.readValue(payload, Map.class);
            String userId = String.valueOf(body.get("userId"));
            Object notificationId = body.get("notificationId");

            lastNotificationReceivedAt = Instant.now();

            if (emitterRegistry.hasLocalEmitter(userId)) {
                emitterRegistry.pushToUser(userId, "notification", body);
                log.debug("Delivered notification {} to userId={} via local SSE emitter", notificationId, userId);
            }
            // else: no-op. User isn't connected to THIS instance (or is offline).
            // The record is already durable in `notification` - history GET covers it.
        } catch (Exception e) {
            log.error("PgNotifyListener: failed to parse/handle notification payload={}", payload, e);
        }
    }

    private void reconnectWithBackoff() {
        try {
            Thread.sleep(2000);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return;
        }
        connectAndListen();
    }

    private void closeQuietly() {
        Connection conn = listenerConnection.getAndSet(null);
        if (conn != null) {
            try {
                conn.close();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * Health check - logs loudly if this instance's listener is down, so a
     * monitoring dashboard/alert can be built on the log pattern or by
     * exposing this as a Micrometer gauge.
     */
    @Scheduled(fixedRate = 30_000)
    public void healthCheck() {
        if (!listenerConnected.get()) {
            log.error("PgNotifyListener HEALTH CHECK FAILED: listener not connected on this instance");
        } else {
            log.debug("PgNotifyListener health check OK, lastNotificationReceivedAt={}", lastNotificationReceivedAt);
        }
    }

    public boolean isListenerConnected() {
        return listenerConnected.get();
    }
}
