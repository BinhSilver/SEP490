package com.jlas.notification.service;

/** Cross-feature contract: features send a recipient ID, never a foreign entity. */
public interface UserNotificationPort {
    void notifyUser(Long userId, String type, String title, String message, String targetUrl, String eventKey);
}
