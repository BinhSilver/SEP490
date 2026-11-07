package com.jlas.admin.service;

import com.jlas.admin.dto.AdminAuditLogView;
import com.jlas.admin.dto.AdminUserView;
import com.jlas.admin.enums.SystemConfigKey;
import com.jlas.admin.repository.AdminAuditLogRepository;
import com.jlas.admin.repository.AdminPaymentRepository;
import com.jlas.admin.repository.AdminUserRepository;
import com.jlas.auth.repository.PremiumPlanRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.IntStream;

/** System Admin overview: accounts, Premium, plans, payments and the latest admin actions. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminDashboardService {
    /** How many calendar years the registration chart shows, the current one included. */
    static final int REGISTRATION_YEARS = 5;

    private final AdminUserRepository users;
    private final PremiumPlanRepository plans;
    private final AdminPaymentRepository payments;
    private final AdminAuditLogRepository logs;
    private final SystemConfigService config;

    public record Revenue(String currency, long count, BigDecimal amount) {
    }
    public record ProductRevenue(String productType, String currency, long count, BigDecimal amount, BigDecimal discountAmount) { }

    /** Year-to-date, last 30 or last 7 days, compared with the same span just before it (a year earlier for YEAR). */
    public enum Period {
        YEAR, DAYS_30, DAYS_7;

        public static Period parse(String value) {
            if (value == null) return YEAR;
            return switch (value.trim().toLowerCase()) {
                case "30d" -> DAYS_30;
                case "7d" -> DAYS_7;
                default -> YEAR;
            };
        }
    }

    /** New accounts and paid revenue of the chosen period and of the period it is compared with. */
    public record PeriodStats(String period, LocalDateTime from, LocalDateTime to,
                              long newUsers, long newUsersPrevious,
                              List<Revenue> paid, List<Revenue> paidPrevious) {
    }

    public record YearCount(int year, long count) {
    }

    public record MonthRevenue(int month, String currency, long count, BigDecimal amount) {
    }

    public record Dashboard(long totalUsers, Map<String, Long> usersByRole, long lockedUsers, long unverifiedUsers,
                            long newUsersLast7Days, long newUsersLast30Days, long premiumActive,
                            long plansOnSale, long plansStopped, Map<String, Long> paymentsByStatus,
                            List<Revenue> paidLast30Days, List<AdminAuditLogView> recentActions,
                            List<ProductRevenue> paidByProductLast30Days, BigDecimal discountLast30Days,
                            PeriodStats periodStats, List<YearCount> registrationsByYear,
                            int premiumYear, List<MonthRevenue> premiumByMonth,
                            long pendingPaymentsOverdue, int pendingAlertMinutes, LocalDateTime generatedAt,
                            List<AdminKpiCatalog.KpiSource> kpiSources, List<AdminAuditLogView> configChanges) {
    }

    /** [JLAS2-51] Audit actions shown in the "recent configuration changes" card. */
    static final List<String> CONFIG_ACTIONS = List.of("CONFIG_UPDATE", "CONFIG_RESET");
    static final int CONFIG_CHANGES_LIMIT = 5;

    /** The requested period, or {@code dashboard.default_period} when the request names none. */
    public Period period(String requested) {
        return Period.parse(requested == null || requested.isBlank()
                ? config.value(SystemConfigKey.DASHBOARD_DEFAULT_PERIOD) : requested);
    }

    public Dashboard summary(Period period) {
        LocalDateTime now = LocalDateTime.now();
        Map<String, Long> byRole = new TreeMap<>();
        users.countByRoleName().forEach(row -> byRole.put((String) row[0], (Long) row[1]));
        Map<String, Long> byStatus = new TreeMap<>();
        payments.countByStatus().forEach(row -> byStatus.put((String) row[0], (Long) row[1]));
        List<Revenue> paid = revenues(payments.paidSince(now.minusDays(30)));
        List<ProductRevenue> byProduct = payments.paidByProductSince(now.minusDays(30)).stream()
                .map(row -> new ProductRevenue((String) row[0], (String) row[1], (Long) row[2],
                        (BigDecimal) row[3], (BigDecimal) row[4])).toList();
        BigDecimal discounts = byProduct.stream().map(ProductRevenue::discountAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        var sellable = plans.findAll().stream()
                .filter(p -> !AdminUserView.FREE_PLAN.equalsIgnoreCase(p.getName())).toList();
        long onSale = sellable.stream().filter(p -> Boolean.TRUE.equals(p.getIsActive())).count();
        int recentLimit = config.intValue(SystemConfigKey.DASHBOARD_RECENT_ACTIONS_LIMIT);
        List<AdminAuditLogView> recent = logs.findAll(PageRequest.of(0, recentLimit,
                Sort.by(Sort.Direction.DESC, "createdAt", "id"))).map(AdminAuditLogView::from).getContent();
        LocalDateTime yearStart = now.toLocalDate().withDayOfYear(1).atStartOfDay();
        List<MonthRevenue> premiumByMonth = payments.premiumPaidByMonth(yearStart, now.plusSeconds(1)).stream()
                .map(row -> new MonthRevenue(((Number) row[0]).intValue(), (String) row[1], (Long) row[2], (BigDecimal) row[3]))
                .toList();
        int alertMinutes = config.intValue(SystemConfigKey.PAYMENT_PENDING_ALERT_MINUTES);
        List<AdminAuditLogView> configChanges = logs.findAll(
                (root, query, cb) -> root.get("action").in(CONFIG_ACTIONS),
                PageRequest.of(0, CONFIG_CHANGES_LIMIT, Sort.by(Sort.Direction.DESC, "createdAt", "id")))
                .map(AdminAuditLogView::from).getContent();
        return new Dashboard(users.count(), byRole, users.countByLockedAtIsNotNull(), users.countByIsVerifiedFalse(),
                users.countByCreatedAtGreaterThanEqual(now.minusDays(7)),
                users.countByCreatedAtGreaterThanEqual(now.minusDays(30)),
                users.countActivePremium(now), onSale, sellable.size() - onSale, byStatus, paid, recent, byProduct, discounts,
                periodStats(period, now), registrationsByYear(now.toLocalDate()), now.getYear(), premiumByMonth,
                payments.countPendingCreatedBefore(now.minusMinutes(alertMinutes)), alertMinutes, now,
                AdminKpiCatalog.ALL, configChanges);
    }

    private PeriodStats periodStats(Period period, LocalDateTime now) {
        LocalDateTime to = now.plusSeconds(1);
        LocalDateTime from = switch (period) {
            case YEAR -> now.toLocalDate().withDayOfYear(1).atStartOfDay();
            case DAYS_30 -> now.minusDays(30);
            case DAYS_7 -> now.minusDays(7);
        };
        // YEAR compares with the same dates of last year; the day windows with the window right before them.
        LocalDateTime previousFrom = period == Period.YEAR ? from.minusYears(1) : from.minus(Duration.between(from, to));
        LocalDateTime previousTo = period == Period.YEAR ? to.minusYears(1) : from;
        return new PeriodStats(period.name(), from, now,
                users.countByCreatedAtGreaterThanEqualAndCreatedAtLessThan(from, to),
                users.countByCreatedAtGreaterThanEqualAndCreatedAtLessThan(previousFrom, previousTo),
                revenues(payments.paidBetween(from, to)), revenues(payments.paidBetween(previousFrom, previousTo)));
    }

    /** One entry per year for the last {@link #REGISTRATION_YEARS} years, zero when nobody signed up that year. */
    private List<YearCount> registrationsByYear(LocalDate today) {
        int firstYear = today.getYear() - REGISTRATION_YEARS + 1;
        Map<Integer, Long> counts = new TreeMap<>();
        users.countCreatedByYearSince(LocalDate.of(firstYear, 1, 1).atStartOfDay())
                .forEach(row -> counts.put(((Number) row[0]).intValue(), (Long) row[1]));
        List<YearCount> years = new ArrayList<>();
        IntStream.rangeClosed(firstYear, today.getYear()).forEach(y -> years.add(new YearCount(y, counts.getOrDefault(y, 0L))));
        return years;
    }

    private static List<Revenue> revenues(List<Object[]> rows) {
        return rows.stream().map(row -> new Revenue((String) row[0], (Long) row[1], (BigDecimal) row[2])).toList();
    }
}
