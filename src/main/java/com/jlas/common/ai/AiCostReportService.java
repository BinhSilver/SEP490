package com.jlas.common.ai;

import com.jlas.shadowing.service.PronunciationQuotaService;
import com.jlas.shadowing.service.ShadowingCreateQuotaService;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Báo cáo chi phí AI theo tháng (kế hoạch Pro/CP, mục 2 "Quy trình chốt giá", bước 2–3 và 6): đọc ai_usage_log, quy
 * từng lần gọi ra tiền, lấy trung bình và P90 của mỗi chức năng rồi gợi ý giá CP theo công thức
 * {@code làm tròn lên bội số của 5 (chi phí P90 × hệ số ÷ giá 1 CP)}. OpenRouter tự trả chi phí USD; Fish Audio và
 * Azure được tính theo bảng giá trong cấu hình {@code ai-pricing}.
 */
@Service
public class AiCostReportService {
    /** Names logged before each AI Call helper had its own (see {@link AiFeature}). */
    static final Map<String, String> OLD_LABELS = Map.of("AI_CALL_HELPER", "AI Call: gợi ý, dịch, furigana (tên cũ)");
    /** The calls that answer the learner: one per turn. Every other AI_CALL_* call is spread over the turns. */
    static final List<String> TURN_REPLIES = List.of(AiFeature.AI_CALL_REPLY.name(), AiFeature.AI_CALL_MEMORY_CHAT.name());

    private final JdbcTemplate jdbc;
    private final double usdToVnd;
    private final double fishUsdPerMillionBytes;
    private final double azureUsdPerHour;
    private final double markup;
    private final double vndPerCredit;
    private final int aiCallCreditPerTurn;

    public AiCostReportService(JdbcTemplate jdbc,
                               @Value("${ai-pricing.usd-to-vnd:26000}") double usdToVnd,
                               @Value("${ai-pricing.fish-audio-usd-per-million-bytes:0}") double fishUsdPerMillionBytes,
                               @Value("${ai-pricing.azure-pronunciation-usd-per-hour:1.32}") double azureUsdPerHour,
                               @Value("${ai-pricing.markup:3}") double markup,
                               @Value("${ai-pricing.vnd-per-credit:100}") double vndPerCredit,
                               @Value("${aicall.quota.credit-per-turn:10}") int aiCallCreditPerTurn) {
        this.jdbc = jdbc;
        this.usdToVnd = usdToVnd;
        this.fishUsdPerMillionBytes = fishUsdPerMillionBytes;
        this.azureUsdPerHour = azureUsdPerHour;
        this.markup = markup;
        this.vndPerCredit = vndPerCredit;
        this.aiCallCreditPerTurn = aiCallCreditPerTurn;
    }

    /**
     * One line per feature: {@code calls} logged, {@code pricedCalls} whose cost is known, and VND per call.
     * {@code suggestedCredit} applies the plan's formula to the P90; {@code currentCredit} is today's price, or null
     * when the feature is not sold by the call.
     */
    public record Line(String feature, String label, long calls, long pricedCalls, double totalVnd, double averageVnd,
                       double p90Vnd, Integer suggestedCredit, Integer currentCredit) {
    }

    /** {@code turns}: AI Call turns (one reply each); the per-turn line adds helpers and voice spread over them. */
    public record Report(String month, double usdToVnd, double markup, double vndPerCredit, List<Line> features,
                         Line aiCallTurn, Line shadowingMinute) {
    }

    @Transactional(readOnly = true)
    public Report report(YearMonth month) {
        LocalDateTime from = month.atDay(1).atStartOfDay();
        LocalDateTime to = month.plusMonths(1).atDay(1).atStartOfDay();
        Map<String, List<Double>> costs = new LinkedHashMap<>();
        Map<String, Long> calls = new LinkedHashMap<>();
        jdbc.query("SELECT feature, provider, cost_usd, characters, audio_seconds FROM ai_usage_log "
                        + "WHERE created_at >= ? AND created_at < ?",
                row -> {
                    String feature = row.getString("feature");
                    calls.merge(feature, 1L, Long::sum);
                    Double vnd = costVnd(row.getString("provider"), row.getBigDecimal("cost_usd"),
                            (Integer) row.getObject("characters", Integer.class), row.getBigDecimal("audio_seconds"));
                    List<Double> list = costs.computeIfAbsent(feature, key -> new ArrayList<>());
                    if (vnd != null) {
                        list.add(vnd);
                    }
                }, Timestamp.valueOf(from), Timestamp.valueOf(to));

        List<Line> features = new ArrayList<>();
        calls.forEach((feature, count) -> features.add(line(feature, OLD_LABELS.getOrDefault(feature, AiFeature.labelOf(feature)), count,
                costs.getOrDefault(feature, List.of()), currentCredit(feature))));
        features.sort(Comparator.comparingDouble(Line::totalVnd).reversed());

        List<Double> shadowing = new ArrayList<>();
        costs.forEach((feature, list) -> {
            if (feature.startsWith("SHADOWING_")) {
                shadowing.addAll(list);
            }
        });
        return new Report(month.toString(), usdToVnd, markup, vndPerCredit, features, aiCallTurn(costs, calls),
                spreadOver("SHADOWING_MINUTE", "Shadowing: một phút video tạo mới", shadowing,
                        countVideoMinutes(from, to), ShadowingCreateQuotaService.CREDIT_PER_MINUTE));
    }

    /**
     * A turn is one reply plus its share of helper and voice calls. P90 of a turn = P90 of the reply + the average
     * helper and voice cost per turn, which keeps the long-reply tail without needing a turn id in the log.
     */
    private Line aiCallTurn(Map<String, List<Double>> costs, Map<String, Long> calls) {
        List<Double> replies = new ArrayList<>();
        long turns = 0;
        double extraTotal = 0;
        for (Map.Entry<String, List<Double>> entry : costs.entrySet()) {
            if (TURN_REPLIES.contains(entry.getKey())) {
                replies.addAll(entry.getValue());
                turns += calls.getOrDefault(entry.getKey(), 0L);
            } else if (entry.getKey().startsWith("AI_CALL_")) {
                extraTotal += sum(entry.getValue());
            }
        }
        if (turns == 0) {
            return new Line("AI_CALL_TURN", "AI Call: một lượt", 0, 0, 0, 0, 0, null, aiCallCreditPerTurn);
        }
        double extras = extraTotal / turns;
        double total = sum(replies) + extras * turns;
        double p90 = percentile(replies, 0.9) + extras;
        return new Line("AI_CALL_TURN", "AI Call: một lượt", turns, replies.size(), total,
                replies.isEmpty() ? 0 : sum(replies) / replies.size() + extras, p90, suggest(p90),
                aiCallCreditPerTurn);
    }

    /**
     * A cost per minute of video: the month's Shadowing AI cost (subtitles, questions, exercises) spread over the minutes
     * of the videos users created that month (the log has no video id).
     */
    private Line spreadOver(String key, String label, List<Double> costs, long units, int currentCredit) {
        double total = sum(costs);
        double average = units == 0 ? 0 : total / units;
        return new Line(key, label, units, costs.size(), total, average, average, units == 0 ? null : suggest(average),
                currentCredit);
    }

    private Line line(String feature, String label, long count, List<Double> costs, Integer currentCredit) {
        double total = sum(costs);
        double p90 = percentile(costs, 0.9);
        return new Line(feature, label, count, costs.size(), total, costs.isEmpty() ? 0 : total / costs.size(), p90,
                currentCredit == null || costs.isEmpty() ? null : suggest(p90), currentCredit);
    }

    /** Làm tròn lên bội số của 5 (chi phí × hệ số ÷ giá 1 CP), at least 5. */
    int suggest(double costVnd) {
        double credits = costVnd * markup / vndPerCredit;
        return Math.max(5, (int) Math.ceil(credits / 5.0) * 5);
    }

    Double costVnd(String provider, BigDecimal costUsd, Integer characters, BigDecimal audioSeconds) {
        if (costUsd != null) {
            return costUsd.doubleValue() * usdToVnd;
        }
        if ("fish-audio".equals(provider) && characters != null) {
            return characters / 1_000_000.0 * fishUsdPerMillionBytes * usdToVnd;
        }
        if ("azure".equals(provider) && audioSeconds != null) {
            return audioSeconds.doubleValue() / 3600.0 * azureUsdPerHour * usdToVnd;
        }
        return null;
    }

    private static Integer currentCredit(String feature) {
        return "PRONUNCIATION_CHECK".equals(feature) ? PronunciationQuotaService.CREDIT_PRICE : null;
    }

    /** Whole minutes of the videos users (not the seed) created in the period. */
    private long countVideoMinutes(LocalDateTime from, LocalDateTime to) {
        try {
            Long count = jdbc.queryForObject("SELECT COALESCE(SUM(CEIL(duration_seconds / 60)), 0) FROM video "
                    + "WHERE created_at >= ? AND created_at < ? AND owner_id IS NOT NULL", Long.class,
                    Timestamp.valueOf(from), Timestamp.valueOf(to));
            return count == null ? 0 : count;
        } catch (RuntimeException missingTable) {
            return 0;
        }
    }

    static double percentile(List<Double> values, double fraction) {
        if (values.isEmpty()) {
            return 0;
        }
        List<Double> sorted = values.stream().sorted().toList();
        int index = (int) Math.ceil(fraction * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
    }

    private static double sum(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).sum();
    }
}
