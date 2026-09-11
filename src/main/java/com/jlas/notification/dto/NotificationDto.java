package com.jlas.notification.dto;

import java.time.LocalDateTime;
import lombok.*;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class NotificationDto {
    private Long id;
    private String type;
    private String title;
    private String message;
    private String targetUrl;
    private boolean read;
    private LocalDateTime createdAt;
}
