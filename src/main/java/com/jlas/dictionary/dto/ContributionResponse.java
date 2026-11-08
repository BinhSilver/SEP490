package com.jlas.dictionary.dto;

import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Builder
public class ContributionResponse {
    private Long id;
    private String content;
    private String authorName;
    private long likes;
    private long dislikes;
    private int myVote;
    private boolean mine;
    private LocalDateTime createdAt;
}
