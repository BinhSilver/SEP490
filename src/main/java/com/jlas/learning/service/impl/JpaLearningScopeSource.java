package com.jlas.learning.service.impl;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.dictionary.entity.Textbook;
import com.jlas.dictionary.repository.TextbookRepository;
import com.jlas.learning.dto.LearningCatalogScope;
import com.jlas.learning.dto.LearningProfileSnapshot;
import com.jlas.learning.dto.LearningStageScope;
import com.jlas.learning.service.LearningScopeSource;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * [JLAS-W0108] P6 — resolve phạm vi giáo trình nhiều chặng từ sách JLPT đã xuất bản.
 *
 * <p>Lộ trình JLPT chia thành các chặng liên tiếp (D5): mỗi chặng nhắm đúng một cấp mục tiêu.
 * N5→N4 là một chặng N4; N5→N3 là hai chặng N4 rồi N3; N2→N1 là một chặng N1; N1→N1 (củng cố
 * chính cấp) là một chặng N1. Warm-up chỉ thuộc chặng đầu tiên, từ điểm yếu ở cấp đã kết luận
 * (hoặc bảng chữ cái nền tảng cho người from-zero).
 *
 * <p>Khác bản cũ: <b>không còn fallback demo seed</b> (id &gt;= 9000). Cấp trong lộ trình thiếu
 * học liệu đã duyệt trả {@link ErrorCode#BLOCKED_CONTENT} minh bạch thay vì âm thầm nhét sách demo
 * hoặc bỏ chặng — học liệu demo không phải bằng chứng đủ giáo trình (R6/R8).
 */
@Component
@Primary
@RequiredArgsConstructor
public class JpaLearningScopeSource implements LearningScopeSource {
    private static final List<String> LEVELS = List.of("BELOW_N5", "N5", "N4", "N3", "N2", "N1");

    private final TextbookRepository textbooks;

    @Override
    @Transactional(readOnly = true)
    public LearningCatalogScope resolve(Long userId, LearningProfileSnapshot profile) {
        if (!"JLPT".equals(profile.goalCode())) {
            throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE,
                    "Curriculum scope is only available for the JLPT goal.");
        }
        boolean fromZero = "BELOW_N5".equals(profile.assessedLevel());
        int assessedIdx = LEVELS.indexOf(profile.assessedLevel());
        int targetIdx = LEVELS.indexOf(profile.targetLevel());
        if (assessedIdx < 0 || targetIdx < 1) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Valid assessed/target levels are required.");
        }

        // Warm-up: from-zero = sách nền tảng bảng chữ cái; còn lại = sách ở cấp đã kết luận (điểm yếu).
        List<Long> warmUpIds = fromZero
                ? textbooks.findKanaFoundationByStatus("PUBLISHED").stream().map(Textbook::getId).toList()
                : realBookIds(List.of(profile.assessedLevel()));

        // Chặng mục tiêu: củng cố khi target == assessed, ngược lại là các cấp ngay trên assessed tới target.
        List<String> stageLevels = targetIdx == assessedIdx
                ? List.of(profile.assessedLevel())
                : LEVELS.subList(assessedIdx + 1, targetIdx + 1);

        List<LearningStageScope> stages = new ArrayList<>();
        List<Long> goalIds = new ArrayList<>();
        for (String level : stageLevels) {
            List<Textbook> books = realBooks(List.of(level));
            if (books.isEmpty()) {
                // [JLAS-W0108] P6/R6 — thiếu cấp (giữa hoặc đích) trả BLOCKED_CONTENT, không fallback demo.
                throw new ApiException(ErrorCode.BLOCKED_CONTENT,
                        "Chưa có đủ học liệu đã duyệt cho cấp độ " + level + ".");
            }
            List<Long> ids = books.stream().map(Textbook::getId).toList();
            stages.add(new LearningStageScope(level, curriculumVersion(level, books), ids));
            goalIds.addAll(ids);
        }
        return new LearningCatalogScope(warmUpIds, goalIds, stages);
    }

    /** [JLAS-W0108] Hướng B — chỉ textbook đã đánh cờ {@code is_learning_path_source} mới được
     * dùng, không còn loại demo seed bằng khoảng id (cờ này đã thay hẳn vai trò đó). */
    private List<Textbook> realBooks(List<String> levels) {
        return textbooks.findByJlptLevelInAndStatusAndIsLearningPathSourceTrueOrderByIdAsc(levels, "PUBLISHED");
    }

    private List<Long> realBookIds(List<String> levels) {
        return realBooks(levels).stream().map(Textbook::getId).toList();
    }

    /** [JLAS-W0108] P6 — fingerprint bộ sách đã duyệt cho cấp này; P8 ghim manifest readiness thật. */
    private String curriculumVersion(String level, List<Textbook> books) {
        int maxVersion = books.stream().mapToInt(Textbook::getVersion).max().orElse(1);
        return level + "-v" + maxVersion;
    }
}
