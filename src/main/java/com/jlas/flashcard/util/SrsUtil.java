package com.jlas.flashcard.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Spaced Repetition (SuperMemo SM-2) cho flashcard, khớp thang chấm 0–5 của màn học.
 *
 * <ul>
 *   <li>EF' = EF + (0.1 − (5−q)·(0.08 + (5−q)·0.02)), không nhỏ hơn 1.3.</li>
 *   <li>q &lt; 3: quên → lặp lại từ đầu (repetition = 0, ôn lại sau 1 ngày).</li>
 *   <li>q ≥ 3: repetition 1 → 1 ngày, 2 → 6 ngày, sau đó interval = interval trước × EF'.</li>
 * </ul>
 *
 * Lịch tính theo ngày: thẻ đến hạn từ 00:00 của ngày ôn, nên ôn sớm vài giờ trong ngày vẫn được tính.
 * Chấm đúng một thẻ chưa đến hạn (ôn dồn trong cùng phiên, quiz lại) không kéo dài lịch;
 * chấm sai thì vẫn tính là quên vì đó là tín hiệu thật.
 */
public final class SrsUtil {
    public static final BigDecimal DEFAULT_EASINESS = new BigDecimal("2.50");
    public static final BigDecimal MIN_EASINESS = new BigDecimal("1.30");
    public static final int MAX_INTERVAL_DAYS = 36500;
    /** Số lần nhớ liên tiếp để coi thẻ là đã thuộc (giữ nguyên mốc cũ của UI). */
    public static final int MASTERED_REPETITIONS = 3;
    public static final int PASS_QUALITY = 3;

    private SrsUtil() { }

    /** Trạng thái SRS hiện tại của một thẻ; null-safe cho bản ghi mới. */
    public record State(int repetitions, int intervalDays, BigDecimal easiness, LocalDateTime nextReviewAt) {
        public State {
            repetitions = Math.max(0, repetitions);
            intervalDays = Math.min(MAX_INTERVAL_DAYS, Math.max(0, intervalDays));
            easiness = easiness == null ? DEFAULT_EASINESS : easiness;
        }
        public static State of(Integer repetitions, Integer intervalDays, BigDecimal easiness, LocalDateTime nextReviewAt) {
            return new State(repetitions == null ? 0 : repetitions, intervalDays == null ? 0 : intervalDays, easiness, nextReviewAt);
        }
    }

    /**
     * Kết quả sau một lần chấm.
     *
     * @param scheduled false khi lần chấm chỉ là ôn sớm (thẻ chưa đến hạn) nên lịch được giữ nguyên
     */
    public record Schedule(String status, int repetitions, int intervalDays, BigDecimal easiness,
                           LocalDateTime nextReviewAt, boolean scheduled) { }

    public static int clampQuality(int quality) { return Math.max(0, Math.min(5, quality)); }

    public static boolean isDue(LocalDateTime nextReviewAt, LocalDateTime now) {
        return nextReviewAt == null || !nextReviewAt.isAfter(now);
    }

    public static Schedule review(State state, int quality, LocalDateTime now) {
        int q = clampQuality(quality);
        boolean pass = q >= PASS_QUALITY;
        if (pass && state.repetitions() > 0 && !isDue(state.nextReviewAt(), now)) {
            return new Schedule(status(state.repetitions()), state.repetitions(), state.intervalDays(),
                    state.easiness(), state.nextReviewAt(), false);
        }
        BigDecimal easiness = nextEasiness(state.easiness(), q);
        int repetitions;
        int interval;
        if (!pass) {
            repetitions = 0;
            interval = 1;
        } else {
            repetitions = state.repetitions() + 1;
            interval = switch (repetitions) {
                case 1 -> 1;
                case 2 -> 6;
                default -> multiply(Math.max(1, state.intervalDays()), easiness);
            };
        }
        return new Schedule(pass ? status(repetitions) : "LEARNING", repetitions, interval, easiness,
                dueDate(now, interval), true);
    }

    /** Số ngày tới lần ôn kế tiếp cho từng mức chấm 0–5, để hiển thị trên nút chấm. */
    public static Map<Integer, Integer> preview(State state, LocalDateTime now) {
        Map<Integer, Integer> result = new LinkedHashMap<>();
        LocalDate today = now.toLocalDate();
        for (int q = 0; q <= 5; q++) {
            LocalDateTime next = review(state, q, now).nextReviewAt();
            result.put(q, next == null ? 0 : (int) Math.max(0, ChronoUnit.DAYS.between(today, next.toLocalDate())));
        }
        return result;
    }

    static BigDecimal nextEasiness(BigDecimal current, int quality) {
        double ef = (current == null ? DEFAULT_EASINESS : current).doubleValue();
        int miss = 5 - quality;
        double next = ef + (0.1 - miss * (0.08 + miss * 0.02));
        BigDecimal value = BigDecimal.valueOf(next).setScale(2, RoundingMode.HALF_UP);
        return value.compareTo(MIN_EASINESS) < 0 ? MIN_EASINESS : value;
    }

    private static int multiply(int interval, BigDecimal easiness) {
        long days = Math.round(interval * easiness.doubleValue());
        return (int) Math.min(MAX_INTERVAL_DAYS, Math.max(interval + 1L, days));
    }

    private static LocalDateTime dueDate(LocalDateTime now, int interval) {
        return now.toLocalDate().plusDays(interval).atStartOfDay();
    }

    private static String status(int repetitions) {
        return repetitions >= MASTERED_REPETITIONS ? "MASTERED" : "LEARNING";
    }
}
