package com.jlas.jlptpath.service.impl;

import com.jlas.jlptpath.dto.JpathAdminReportView;
import com.jlas.jlptpath.enums.JpathCheckKind;
import com.jlas.jlptpath.enums.JpathDayKind;
import com.jlas.jlptpath.enums.JpathPathStatus;
import com.jlas.jlptpath.service.JpathAdminReportService;
import com.jlas.jlptpath.service.JpathClock;
import com.jlas.mission.entity.UserStreaks;
import com.jlas.mission.repository.UserStreaksRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * [JLAS-W0108] {@link JpathAdminReportService}. Read-only aggregate queries over the jpath tables; the streak comes
 * from the mission module through its repository; the legacy path count is a read-only native count
 * ({@link JpathLegacyPathCounter}).
 */
@Service
@RequiredArgsConstructor
public class JpathAdminReportServiceImpl implements JpathAdminReportService {

    private static final List<String> LEVELS = List.of("N5", "N4", "N3", "N2", "N1");

    private final EntityManager em;
    private final JpathClock clock;
    private final UserStreaksRepository streaks;
    private final JpathLegacyPathCounter legacyPaths;

    @Override
    @Transactional(readOnly = true)
    public JpathAdminReportView report() {
        LocalDateTime now = clock.now();
        LocalDate today = now.toLocalDate();

        Map<String, Long> byLevel = new LinkedHashMap<>();
        LEVELS.forEach(l -> byLevel.put(l, 0L));
        em.createQuery("select p.openLevel, count(p) from JpathUserPath p where p.status = :active "
                        + "group by p.openLevel", Object[].class)
                .setParameter("active", JpathPathStatus.ACTIVE).getResultList()
                .forEach(row -> byLevel.computeIfPresent((String) row[0], (k, v) -> ((Number) row[1]).longValue()));

        long active7d = em.createQuery("select count(distinct e.userId) from JpathStudyEvent e "
                + "where e.createdAt >= :since", Long.class).setParameter("since", now.minusDays(7)).getSingleResult();

        Object[] plans = em.createQuery("select count(d), sum(case when d.allDoneAt is not null then 1 else 0 end) "
                        + "from JpathDailyPlan d where d.dayKind <> :rest and d.studyDay > :from and d.studyDay <= :to",
                        Object[].class)
                .setParameter("rest", JpathDayKind.REST).setParameter("from", today.minusDays(30))
                .setParameter("to", today).getSingleResult();

        Object[] checks = em.createQuery("select count(c), sum(case when c.passed = true then 1 else 0 end) "
                        + "from JpathCheckSet c where c.kind = :kind and c.submittedAt is not null "
                        + "and c.submittedAt >= :since", Object[].class)
                .setParameter("kind", JpathCheckKind.MICRO_CHECK).setParameter("since", now.minusDays(30))
                .getSingleResult();

        List<Long> activeUsers = em.createQuery("select distinct p.userId from JpathUserPath p "
                + "where p.status = :active", Long.class).setParameter("active", JpathPathStatus.ACTIVE).getResultList();
        List<UserStreaks> rows = activeUsers.isEmpty() ? List.of() : streaks.findAllById(activeUsers);
        double averageStreak = round(rows.stream()
                .mapToInt(s -> s.getCurrentStreak() == null ? 0 : s.getCurrentStreak()).average().orElse(0));

        long mockAttempts = count("select count(l) from JpathUserLevel l where l.mockAttemptId is not null");
        long mockPassed = count("select count(l) from JpathUserLevel l where l.mockAttemptId is not null "
                + "and l.passedAt is not null");
        long reportsOpen = count("select count(r) from JpathContentReport r where r.status = 'OPEN'");
        long reportsTotal = count("select count(r) from JpathContentReport r");

        return new JpathAdminReportView(now, byLevel, active7d, percent(plans), averageStreak, percent(checks),
                mockAttempts, mockPassed, reportsOpen, reportsTotal, legacyPaths.activePaths());
    }

    private long count(String jpql) {
        TypedQuery<Long> query = em.createQuery(jpql, Long.class);
        return query.getSingleResult();
    }

    /** {@code [total, hits]} → 100·hits/total with 2 decimals; null when total is 0. */
    private static Double percent(Object[] totalAndHits) {
        long total = totalAndHits[0] == null ? 0 : ((Number) totalAndHits[0]).longValue();
        long hits = totalAndHits[1] == null ? 0 : ((Number) totalAndHits[1]).longValue();
        return total == 0 ? null : round(100.0 * hits / total);
    }

    private static double round(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }
}
