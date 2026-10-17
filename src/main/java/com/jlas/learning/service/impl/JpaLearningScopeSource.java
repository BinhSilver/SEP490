package com.jlas.learning.service.impl;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.common.learning.JlptPathPolicy;
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
 * Với nhãn Căn bản, N5→N4 là một chặng N4; Nhập môn bắt đầu lại từ cấp survey, và Thành thạo
 * chỉ có chặng target. Warm-up lỗi sai thuộc nhánh Căn bản cần chuyển cấp;
 * Căn bản cùng cấp học full cấp đó, from-zero giữ bảng chữ cái nền tảng.
 *
 * <p>Khác bản cũ: <b>không còn fallback demo seed</b> (id &gt;= 9000). Cấp trong lộ trình thiếu
 * học liệu đã duyệt trả {@link ErrorCode#BLOCKED_CONTENT} minh bạch thay vì âm thầm nhét sách demo
 * hoặc bỏ chặng — học liệu demo không phải bằng chứng đủ giáo trình (R6/R8).
 */
@Component
@Primary
@RequiredArgsConstructor
public class JpaLearningScopeSource implements LearningScopeSource {
    private final TextbookRepository textbooks;
    private final JlptPathPolicy pathPolicy = new JlptPathPolicy();

    @Override
    @Transactional(readOnly = true)
    public LearningCatalogScope resolve(Long userId, LearningProfileSnapshot profile) {
        if (!"JLPT".equals(profile.goalCode())) {
            throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE,
                    "Curriculum scope is only available for the JLPT goal.");
        }
        boolean fromZero = "BELOW_N5".equals(profile.assessedLevel());
        var eligibility = pathPolicy.evaluate(profile.assessedLevel(), profile.targetLevel());
        if (!eligibility.allowed()) {
            throw new ApiException(ErrorCode.TARGET_LEVEL_NOT_ALLOWED,
                    "Mục tiêu " + profile.targetLevel() + " chưa phù hợp; hệ thống khuyến nghị "
                            + eligibility.recommendedTarget() + ".");
        }

        // Warm-up: from-zero = sách nền tảng bảng chữ cái; nhánh chuyển cấp Căn bản = sách ở cấp đã kết luận.
        List<Long> warmUpIds = fromZero
                ? textbooks.findKanaFoundationByStatus("PUBLISHED").stream().map(Textbook::getId).toList()
                : LearningStartPolicy.needsMistakeWarmUp(profile)
                        ? realBookIds(List.of(profile.assessedLevel())) : List.of();
        if (fromZero && warmUpIds.isEmpty()) {
            throw new ApiException(ErrorCode.BLOCKED_CONTENT,
                    "MISSING_FOUNDATION: Chưa có học liệu bảng chữ cái đã duyệt.");
        }

        List<LearningStageScope> stages = new ArrayList<>();
        List<Long> goalIds = new ArrayList<>();
        if (fromZero) {
            List<Textbook> foundationBooks = textbooks.findKanaFoundationByStatus("PUBLISHED");
            stages.add(new LearningStageScope("FOUNDATION",
                    curriculumVersion("FOUNDATION", foundationBooks), warmUpIds));
        }
        List<String> goalLevels = fromZero
                ? eligibility.stageLevels().stream().filter(level -> !"FOUNDATION".equals(level)).toList()
                : LearningStartPolicy.goalLevels(profile);
        for (String level : goalLevels) {
            List<Textbook> books = realBooks(List.of(level));
            if (books.isEmpty()) {
                // [JLAS-W0108] P6/R6 — thiếu cấp (giữa hoặc đích) trả BLOCKED_CONTENT, không fallback demo.
                throw new ApiException(ErrorCode.BLOCKED_CONTENT,
                        "MISSING_LEVEL_BOOK: Chưa có học liệu đã duyệt cho cấp độ " + level + ".");
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
