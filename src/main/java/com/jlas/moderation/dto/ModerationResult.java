package com.jlas.moderation.dto;

import com.jlas.moderation.model.ModerationVerdict;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class ModerationResult {
    private ModerationVerdict verdict;
    private List<String> categories;
    private double confidence;
    private String summary;
    private String modelName;

    public boolean isSafe() {
        return verdict == ModerationVerdict.SAFE;
    }

    public boolean isBlocked() {
        return verdict == ModerationVerdict.BLOCKED;
    }

    public boolean needsReview() {
        return verdict == ModerationVerdict.REVIEW;
    }
}
