package com.jlas.learning.service.impl;

import java.math.BigDecimal;
import com.jlas.learning.dto.WeaknessEvidence;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * [JLAS-W0108] Chính sách kết luận "yếu" theo skill (spec §2.1).
 *
 * <ul>
 *   <li>KANJI / VOCABULARY: sai chính xác ≥ 1 câu là yếu (per-item, không gom cả bài/lesson).</li>
 *   <li>GRAMMAR: tổng hợp theo grammarPointId; yếu khi sai/tổng ≥ threshold và đủ mẫu.</li>
 *   <li>READING / LISTENING: "sai trên 3 câu" = sai 4–5 trong cửa sổ 5 câu hợp lệ; sai 3/5 chỉ là tín hiệu theo dõi.</li>
 * </ul>
 *
 * <p>{@code NO_RESPONSE} (hết giờ) tính là SAI ở tầng aggregation, không phân biệt ở đây.
 * {@code TECHNICAL_FAILURE} (chưa có cột trong schema) sẽ được loại khỏi mẫu số trước khi vào policy.</p>
 */
@Component
public class WeakTagPolicy {
    /** Cửa sổ câu hợp lệ cho Reading/Listening (batch adaptive = 5). */
    private static final int COMPREHENSION_WINDOW = 5;
    /** "Sai trên 3 câu" = sai 4 hoặc 5 câu trong cửa sổ 5. */
    private static final int COMPREHENSION_WRONG_MIN = 4;

    private final int minimumQuestions;
    private final BigDecimal threshold;

    public WeakTagPolicy(@Value("${jlas.learning.selection.minimum-assessed-questions:1}") int minimumQuestions,
            @Value("${jlas.learning.selection.wrong-ratio-threshold:0.5}") BigDecimal threshold) {
        if (minimumQuestions < 1 || threshold == null || threshold.signum() <= 0
                || threshold.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("Weak-tag policy requires a positive sample size and a ratio in (0, 1].");
        }
        this.minimumQuestions = minimumQuestions;
        this.threshold = threshold.stripTrailingZeros();
    }

    public boolean hasEnoughEvidence(WeaknessEvidence evidence) {
        return switch (evidence.skill()) {
            case WeaknessEvidence.SKILL_KANJI, WeaknessEvidence.SKILL_VOCABULARY -> evidence.wrongCount() >= 1;
            case WeaknessEvidence.SKILL_GRAMMAR -> evidence.assessedCount() >= minimumQuestions;
            case WeaknessEvidence.SKILL_READING, WeaknessEvidence.SKILL_LISTENING ->
                    evidence.assessedCount() >= COMPREHENSION_WINDOW;
            default -> false;
        };
    }

    public boolean isWeak(WeaknessEvidence evidence) {
        return switch (evidence.skill()) {
            case WeaknessEvidence.SKILL_KANJI, WeaknessEvidence.SKILL_VOCABULARY -> evidence.wrongCount() >= 1;
            case WeaknessEvidence.SKILL_GRAMMAR -> hasEnoughEvidence(evidence)
                    && BigDecimal.valueOf(evidence.wrongCount())
                            .compareTo(threshold.multiply(BigDecimal.valueOf(evidence.assessedCount()))) >= 0;
            case WeaknessEvidence.SKILL_READING, WeaknessEvidence.SKILL_LISTENING ->
                    evidence.assessedCount() >= COMPREHENSION_WINDOW && evidence.wrongCount() >= COMPREHENSION_WRONG_MIN;
            default -> false;
        };
    }

    public String version() {
        return "jlpt-per-skill-v2:min=" + minimumQuestions + ":ratio=" + threshold.toPlainString()
                + ":window=" + COMPREHENSION_WINDOW + "/" + COMPREHENSION_WRONG_MIN;
    }
}
