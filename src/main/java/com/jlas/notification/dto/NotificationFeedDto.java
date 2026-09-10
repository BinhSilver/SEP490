package com.jlas.notification.dto;

import java.util.List;
import lombok.*;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class NotificationFeedDto {
    private List<NotificationDto> items;
    private long unreadCount;
}
