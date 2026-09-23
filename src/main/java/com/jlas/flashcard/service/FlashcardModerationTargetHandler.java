package com.jlas.flashcard.service;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.flashcard.entity.FlashcardSet;
import com.jlas.flashcard.repository.FlashcardSetRepository;
import com.jlas.moderation.dto.ModerationTargetSnapshot;
import com.jlas.moderation.entity.ContentComment;
import com.jlas.moderation.repository.ContentCommentRepository;
import com.jlas.moderation.service.ModerationTargetHandler;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class FlashcardModerationTargetHandler implements ModerationTargetHandler {
    private final FlashcardSetRepository sets;
    private final ContentCommentRepository comments;

    @Override
    public boolean supports(String targetType) {
        return "FLASHCARD_SET".equals(targetType) || "FLASHCARD_COMMENT".equals(targetType);
    }

    @Override
    @Transactional(readOnly = true)
    public ModerationTargetSnapshot snapshot(String targetType, Long targetId) {
        if ("FLASHCARD_SET".equals(targetType)) {
            return sets.findById(targetId)
                    .map(set -> new ModerationTargetSnapshot(set.getDescription(),
                            "/flashcard/study.html?setId=" + set.getId()))
                    .orElseGet(ModerationTargetSnapshot::empty);
        }
        return comments.findById(targetId)
                .map(comment -> new ModerationTargetSnapshot(comment.getContent(),
                        "/flashcard/study.html?setId=" + comment.getTargetId()))
                .orElseGet(ModerationTargetSnapshot::empty);
    }

    @Override
    @Transactional
    public void resolve(String targetType, Long targetId, boolean approve, String note) {
        if ("FLASHCARD_SET".equals(targetType)) {
            FlashcardSet set = sets.findById(targetId)
                    .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Bộ thẻ không còn tồn tại"));
            if (set.getDeletedAt() != null) {
                throw new ApiException(ErrorCode.NOT_FOUND, "Bộ thẻ đã bị xóa");
            }
            set.setModerationStatus(approve ? "SAFE" : "BLOCKED");
            set.setModerationSummary(approve ? "Moderator đã duyệt" : fallback(note, "Moderator từ chối"));
            set.setModerationCheckedAt(LocalDateTime.now());
            set.setVisibility(approve ? "PUBLIC" : "PRIVATE");
            sets.save(set);
            return;
        }
        ContentComment comment = comments.findById(targetId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Bình luận không còn tồn tại"));
        comment.setIsHidden(!approve);
        comments.save(comment);
    }

    private static String fallback(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }
}
