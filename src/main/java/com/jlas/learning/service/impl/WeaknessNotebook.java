package com.jlas.learning.service.impl;

import com.jlas.auth.entity.User;
import com.jlas.dictionary.entity.Outcome;
import com.jlas.dictionary.entity.Tag;
import com.jlas.learning.dto.WeaknessEvidence;
import com.jlas.learning.entity.UserKnowledgeWeakness;
import com.jlas.learning.repository.UserKnowledgeWeaknessRepository;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * [JLAS-W0108] Lát 2.4 — Sổ tay điểm yếu (Weakness Notebook): ghi lại các điểm yếu kết luận được
 * (policy {@code isWeak}) sau mỗi Smart Test thành dòng {@link UserKnowledgeWeakness} bền vững, key
 * theo canonical item (spec §2.4). Nguồn ghi rõ {@code SMART_TEST:<attemptId>} để audit.
 *
 * <p>Idempotent: cùng một natural key thì cập nhật wrong_count/score/severity thay vì chèn trùng.
 * Natural key khớp trong bộ nhớ trên tập dòng của một user (không dùng native upsert) để chạy
 * được cả trên H2 (test) lẫn MySQL.</p>
 */
@Component
@RequiredArgsConstructor
public class WeaknessNotebook {

    private final UserKnowledgeWeaknessRepository weaknesses;
    private final EntityManager em;

    /** Ghi các điểm yếu đã kết luận (isWeak) từ attempt; source = {@code SMART_TEST:<attemptId>}. */
    public void record(User user, Long assessmentAttemptId, List<WeaknessEvidence> weak) {
        if (weak == null || weak.isEmpty()) {
            return;
        }
        String source = "SMART_TEST:" + assessmentAttemptId;
        Map<Key, UserKnowledgeWeakness> existing = weaknesses.findByUserId(user.getId()).stream()
                .collect(Collectors.toMap(Key::of, Function.identity(), (first, second) -> first));
        Map<Key, UserKnowledgeWeakness> staged = new HashMap<>(existing);
        for (WeaknessEvidence evidence : weak) {
            Key key = Key.of(evidence);
            UserKnowledgeWeakness row = staged.get(key);
            if (row == null) {
                row = UserKnowledgeWeakness.builder()
                        .user(user)
                        .tag(ref(Tag.class, evidence.tagId()))
                        .canonicalItem(evidence.canonicalId())
                        .skill(evidence.skill())
                        .jlptLevel(evidence.jlptLevel())
                        .topicTag(ref(Tag.class, evidence.topicTagId()))
                        .outcome(ref(Outcome.class, evidence.outcomeId()))
                        .status("OPEN")
                        .build();
                staged.put(key, row);
            }
            row.setWrongCount(evidence.wrongCount());
            row.setScorePercent(accuracy(evidence));
            row.setSeverity("WEAK");
            row.setSource(source);
            weaknesses.save(row);
        }
    }

    /** Độ chính xác (%) 2 chữ số thập phân; null khi không có mẫu đánh giá. */
    private static BigDecimal accuracy(WeaknessEvidence evidence) {
        if (evidence.assessedCount() <= 0) {
            return null;
        }
        return BigDecimal.valueOf((long) (evidence.assessedCount() - evidence.wrongCount()) * 100)
                .divide(BigDecimal.valueOf(evidence.assessedCount()), 2, RoundingMode.HALF_UP);
    }

    private <T> T ref(Class<T> type, Long id) {
        return id == null ? null : em.getReference(type, id);
    }

    /** Natural key khớp unique key (user, tag, canonical_item, jlpt_level, topic, outcome). */
    private record Key(Long tagId, String canonicalItem, String jlptLevel, Long topicTagId, Long outcomeId) {
        static Key of(WeaknessEvidence evidence) {
            return new Key(evidence.tagId(), evidence.canonicalId(), evidence.jlptLevel(),
                    evidence.topicTagId(), evidence.outcomeId());
        }

        static Key of(UserKnowledgeWeakness row) {
            return new Key(row.getTag() == null ? null : row.getTag().getId(),
                    row.getCanonicalItem(), row.getJlptLevel(),
                    row.getTopicTag() == null ? null : row.getTopicTag().getId(),
                    row.getOutcome() == null ? null : row.getOutcome().getId());
        }
    }
}
