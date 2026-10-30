package com.jlas.notification.repository;

import com.jlas.notification.entity.UserNotification;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserNotificationRepository extends JpaRepository<UserNotification, Long> {
    List<UserNotification> findTop50ByUserIdOrderByCreatedAtDesc(Long userId);
    long countByUserIdAndReadAtIsNull(Long userId);
    Optional<UserNotification> findByUserIdAndEventKey(Long userId, String eventKey);

    /** Marks every unread notification of the user, not only the 50 shown in the panel. */
    @Modifying
    @Query("update UserNotification n set n.readAt = :now where n.user.id = :userId and n.readAt is null")
    int markAllRead(@Param("userId") Long userId, @Param("now") LocalDateTime now);

    Page<UserNotification> findByUserIdOrderByCreatedAtDescIdDesc(Long userId, Pageable pageable);

    Page<UserNotification> findByUserIdAndReadAtIsNullOrderByCreatedAtDescIdDesc(Long userId, Pageable pageable);

    /** Retention: one bulk statement instead of loading rows. */
    @Modifying
    @Query("delete from UserNotification n where n.createdAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") LocalDateTime cutoff);
}
