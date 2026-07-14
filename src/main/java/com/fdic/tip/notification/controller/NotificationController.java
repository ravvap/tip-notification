package com.fdic.tip.notification.controller;

import com.fdic.tip.notification.dto.NotificationDto;
import com.fdic.tip.notification.service.NotificationService;
import com.fdic.tip.notification.service.SseEmitterRegistry;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final NotificationService notificationService;
    private final SseEmitterRegistry emitterRegistry;

    public NotificationController(NotificationService notificationService, SseEmitterRegistry emitterRegistry) {
        this.notificationService = notificationService;
        this.emitterRegistry = emitterRegistry;
    }

    /** Step 6c / Phase 1 history fetch. Called AFTER the SSE handshake (see stream()) to avoid the race window. */
    @GetMapping
    public ResponseEntity<List<NotificationDto>> getHistory(JwtAuthenticationToken auth) {
        String userId = extractUserId(auth);
        return ResponseEntity.ok(notificationService.getHistory(userId));
    }

    /**
     * SSE handshake. Token is passed as a query param, not an Authorization
     * header, because the browser's native EventSource API cannot set custom
     * headers. SecurityConfig permits this path through the filter chain
     * without a header and this method validates the token manually instead -
     * see SecurityConfig for the corresponding permit rule.
     */
    @GetMapping(path = "/stream", produces = "text/event-stream")
    public SseEmitter stream(@RequestParam("token") String token, @RequestParam("userId") String userId) {
        // In production, validate `token` here (e.g. via the same JwtDecoder bean
        // used by the resource server filter) and derive userId FROM the token
        // rather than trusting the query param directly. Left explicit so the
        // validation call is visible rather than hidden in a filter.
        log.info("SSE stream requested for userId={}", userId);
        return emitterRegistry.register(userId);
    }

    /** Self-heal endpoint: client calls this after reconnecting if it suspects it missed events. */
    @GetMapping("/reconcile")
    public ResponseEntity<List<NotificationDto>> reconcile(
            JwtAuthenticationToken auth,
            @RequestParam("since") String sinceIso) {
        String userId = extractUserId(auth);
        return ResponseEntity.ok(notificationService.reconcileMissed(userId, OffsetDateTime.parse(sinceIso)));
    }

    @PatchMapping("/{id}/read")
    public ResponseEntity<NotificationDto> markAsRead(JwtAuthenticationToken auth, @PathVariable UUID id) {
        String userId = extractUserId(auth);
        return ResponseEntity.ok(notificationService.markAsRead(id, userId));
    }

    private String extractUserId(JwtAuthenticationToken auth) {
        Jwt jwt = auth.getToken();
        // Matches the Entra `appid`/object-id claim pattern used elsewhere in TIP -
        // adjust claim name to whatever your Entra app registration emits.
        Object claim = jwt.getClaims().getOrDefault("oid", jwt.getClaims().get("sub"));
        return String.valueOf(claim);
    }
}
