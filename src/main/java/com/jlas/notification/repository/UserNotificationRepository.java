package com.jlas.notification.repository;

import com.jlas.notification.entity.UserNotification;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserNotificationRepository extends JpaRepository<UserNotification, Long> {
    List<UserNotification> findTop50ByUserIdOrderByCreatedAtDesc(Long userId);
    long countByUserIdAndReadAtIsNull(Long userId);
    Optional<UserNotification> findByUserIdAndEventKey(Long userId, String eventKey);
}
