package com.fdic.tip.notification.repository;

import com.fdic.tip.notification.entity.NotificationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<NotificationEntity, UUID> {

    List<NotificationEntity> findByUserIdOrderByCreatedAtDesc(String userId);

    Optional<NotificationEntity> findByIdAndUserId(UUID id, String userId);

    // Used by NotificationController.reconcile() so a client can self-heal after
    // a reconnect - catches anything it may have missed while disconnected.
    @Query("select n from NotificationEntity n where n.userId = :userId and n.createdAt > :since order by n.createdAt asc")
    List<NotificationEntity> findMissedSince(@Param("userId") String userId, @Param("since") OffsetDateTime since);

    // Used by NotificationPollingService: scoped to only the users currently connected
    // to THIS instance, and only rows created since the last poll tick.
    @Query("select n from NotificationEntity n where n.userId in :userIds and n.createdAt > :since order by n.createdAt asc")
    List<NotificationEntity> findByUserIdInAndCreatedAtAfter(@Param("userIds") java.util.Collection<String> userIds,
                                                              @Param("since") OffsetDateTime since);

    @Modifying
    @Query("update NotificationEntity n set n.read = true, n.readAt = CURRENT_TIMESTAMP where n.id = :id and n.userId = :userId")
    int markAsRead(@Param("id") UUID id, @Param("userId") String userId);
}
