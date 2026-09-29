package com.jlas.notification.service;

import com.jlas.auth.repository.UserRepository;
import com.jlas.notification.dto.*;
import com.jlas.notification.entity.UserNotification;
import com.jlas.notification.repository.UserNotificationRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
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

    /** Once-a-day greeting, sent at login (reading the feed must not write data). */
    @Override
    public void greetDaily(Long userId, String fullName) {
        String name = fullName == null || fullName.isBlank() ? "bạn" : fullName.trim();
        notifyUser(userId, "WELCOME", "Chào mừng bạn quay lại", "Chúc mừng bạn quay lại, " + name + "!", "/",
                "WELCOME_" + LocalDate.now());
    }

    @Transactional(readOnly = true)
    public NotificationFeedDto feed(Long userId) {
        List<NotificationDto> items = notifications.findTop50ByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(this::dto).toList();
        return NotificationFeedDto.builder().items(items)
                .unreadCount(notifications.countByUserIdAndReadAtIsNull(userId)).build();
    }

    @Transactional(readOnly = true)
    public NotificationPageDto history(Long userId, boolean unreadOnly, int page, int size) {
        PageRequest request = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 50));
        Page<UserNotification> result = unreadOnly
                ? notifications.findByUserIdAndReadAtIsNullOrderByCreatedAtDescIdDesc(userId, request)
                : notifications.findByUserIdOrderByCreatedAtDescIdDesc(userId, request);
        return new NotificationPageDto(result.getContent().stream().map(this::dto).toList(), result.getNumber(),
                result.getSize(), result.getTotalElements(), result.getTotalPages(),
                notifications.countByUserIdAndReadAtIsNull(userId));
    }

    /** Deletes notifications older than the retention window; returns how many were removed. */
    public int purgeOlderThan(LocalDateTime cutoff) {
        return notifications.deleteOlderThan(cutoff);
    }

    @Transactional(readOnly = true)
    public long unreadCount(Long userId) {
        return notifications.countByUserIdAndReadAtIsNull(userId);
    }

    public void markRead(Long userId, Long notificationId) {
        if (notificationId == null) {
            notifications.markAllRead(userId, LocalDateTime.now());
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
