package com.jlas.shadowing.dto;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class SeriesSummaryDto {
    private Long id;
    private String title;
    private String description;
    private String estimatedLevel;
    private String levelSource;
    private String visibility;
    private Long ownerId;
    private LocalDateTime createdAt;
    private int videoCount;
    private int viewCount;
    private Double avgRating;
    private int ratingCount;
    private boolean ownedByMe;
    private Double duration;
    private List<String> categories;
    /** Video đầu tiên (theo thứ tự trong series) có ảnh bìa; dùng làm thumbnail của thẻ danh sách. */
    private Long coverVideoId;
}
