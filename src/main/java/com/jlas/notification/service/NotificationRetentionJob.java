package com.jlas.notification.service;

import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Keeps user_notification small: removes notifications older than the retention window every night. */
@Slf4j
@Component
@EnableScheduling
@RequiredArgsConstructor
public class NotificationRetentionJob {
    private final UserNotificationService notifications;

    @Value("${notification.retention-days:90}")
    private int retentionDays = 90;

    @Scheduled(cron = "${notification.retention-cron:0 30 3 * * *}")
    public void purge() {
        int removed = notifications.purgeOlderThan(LocalDateTime.now().minusDays(retentionDays));
        if (removed > 0) log.info("Removed {} notifications older than {} days", removed, retentionDays);
    }
}
