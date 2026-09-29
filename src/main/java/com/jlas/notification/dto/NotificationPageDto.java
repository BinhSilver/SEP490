package com.jlas.notification.dto;

import java.util.List;

/** One page of the full notification history. */
public record NotificationPageDto(List<NotificationDto> items, int page, int size, long totalElements,
                                  int totalPages, long unreadCount) {
}
