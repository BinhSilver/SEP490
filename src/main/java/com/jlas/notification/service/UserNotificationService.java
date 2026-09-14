package com.jlas.notification.service;

import com.jlas.auth.repository.UserRepository;
import com.jlas.notification.dto.*;
import com.jlas.notification.entity.UserNotification;
import com.jlas.notification.repository.UserNotificationRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor @Transactional
public class UserNotificationService implements UserNotificationPort {
    private final UserNotificationRepository notifications;
    private final UserRepository users;

    @Override
    public void notifyUser(Long userId, String type, String title, String message, String targetUrl, String eventKey) {
        if (userId == null) return;
        if (eventKey != null && notifications.findByUserIdAndEventKey(userId, eventKey).isPresent()) return;
        notifications.save(UserNotification.builder().user(users.getReferenceById(userId)).type(type)
                .title(title).message(message).targetUrl(targetUrl).eventKey(eventKey).build());
    }

    public NotificationFeedDto feed(Long userId, String displayName) {
        String key = "WELCOME_" + LocalDate.now();
        notifyUser(userId, "WELCOME", "Chào mừng bạn quay lại",
                "Chúc mừng bạn quay lại, " + (displayName == null || displayName.isBlank() ? "bạn" : displayName) + "!", "/", key);
        List<NotificationDto> items = notifications.findTop50ByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(this::dto).toList();
        return NotificationFeedDto.builder().items(items)
                .unreadCount(notifications.countByUserIdAndReadAtIsNull(userId)).build();
    }

    public void markRead(Long userId, Long notificationId) {
        if (notificationId == null) {
            notifications.findTop50ByUserIdOrderByCreatedAtDesc(userId).forEach(item -> item.setReadAt(LocalDateTime.now()));
            return;
        }
        notifications.findById(notificationId).filter(item -> item.getUser().getId().equals(userId))
                .ifPresent(item -> item.setReadAt(LocalDateTime.now()));
    }

    private NotificationDto dto(UserNotification item) {
        return NotificationDto.builder().id(item.getId()).type(item.getType()).title(item.getTitle())
                .message(displayMessage(item)).targetUrl(displayTargetUrl(item)).read(item.getReadAt() != null)
                .createdAt(item.getCreatedAt()).build();
    }

    private static String displayMessage(UserNotification item) {
        String message = item.getMessage();
        if (message == null || !"SECURITY".equals(item.getType())) return message;
        return message.replaceFirst("\\s*·\\s*IP\\s+[^.]+(?=\\.)", "");
    }

    private static String displayTargetUrl(UserNotification item) {
        return "SECURITY".equals(item.getType()) && "/profile?tab=security".equals(item.getTargetUrl())
                ? "/profile/security/login-alerts" : item.getTargetUrl();
    }
}
