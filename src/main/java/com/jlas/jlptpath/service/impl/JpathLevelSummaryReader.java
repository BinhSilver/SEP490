package com.jlas.jlptpath.service.impl;

import com.jlas.jlptpath.dto.JpathLevelSummary;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * [JLAS-W0108] Counts of the level summary (Plan 3 Task 18), read from the learner's own jpath rows of one level:
 * KNOWN items, learned (counted) items, MICRO_CHECK accuracy, done days, top 10 forgotten items.
 */
@Component
public class JpathLevelSummaryReader {

    static final int WEAK_LIMIT = 10;

    private final EntityManager em;

    public JpathLevelSummaryReader(EntityManager em) {
        this.em = em;
    }

    public record Counts(int known, int learned, BigDecimal accuracyPct, int studyDays,
            List<JpathLevelSummary.WeakItem> weak) { }

    public Counts read(Long userId, String level) {
        int known = count("select count(*) from jpath_user_item where user_id = ?1 and level = ?2 and state = 'KNOWN'",
                userId, level);
        int learned = count("select count(*) from jpath_user_item where user_id = ?1 and level = ?2"
                + " and counted_at is not null", userId, level);
        int days = count("select count(*) from jpath_daily_plan where user_id = ?1 and level = ?2"
                + " and all_done_at is not null", userId, level);
        Object[] acc = (Object[]) em.createNativeQuery("select coalesce(sum(correct_count), 0), coalesce(sum(total_count), 0)"
                        + " from jpath_check_set where user_id = ?1 and level = ?2 and kind = 'MICRO_CHECK'"
                        + " and submitted_at is not null")
                .setParameter(1, userId).setParameter(2, level).getSingleResult();
        long correct = ((Number) acc[0]).longValue();
        long total = ((Number) acc[1]).longValue();
        BigDecimal accuracy = total == 0 ? null
                : BigDecimal.valueOf(correct * 100).divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
        List<?> rows = em.createNativeQuery("select item_type, item_code, forget_count from jpath_user_item"
                        + " where user_id = ?1 and level = ?2 and forget_count > 0"
                        + " order by forget_count desc, item_code")
                .setParameter(1, userId).setParameter(2, level).setMaxResults(WEAK_LIMIT).getResultList();
        List<JpathLevelSummary.WeakItem> weak = new ArrayList<>();
        for (Object o : rows) {
            Object[] r = (Object[]) o;
            weak.add(new JpathLevelSummary.WeakItem(String.valueOf(r[0]), String.valueOf(r[1]),
                    ((Number) r[2]).intValue()));
        }
        return new Counts(known, learned, accuracy, days, List.copyOf(weak));
    }

    private int count(String sql, Long userId, String level) {
        return ((Number) em.createNativeQuery(sql).setParameter(1, userId).setParameter(2, level).getSingleResult())
                .intValue();
    }
}
