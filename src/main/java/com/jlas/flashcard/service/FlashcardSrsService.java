package com.jlas.flashcard.service;

import com.jlas.flashcard.dto.FlashcardDtos.*;
import com.jlas.flashcard.entity.FlashcardCard;
import com.jlas.flashcard.entity.FlashcardCardProgress;
import com.jlas.flashcard.entity.FlashcardCardProgress.FlashcardCardProgressId;
import com.jlas.flashcard.mapper.FlashcardMapper;
import com.jlas.flashcard.repository.FlashcardCardProgressRepository;
import com.jlas.flashcard.repository.FlashcardReviewLogStore;
import com.jlas.flashcard.repository.FlashcardReviewLogStore.Totals;
import com.jlas.flashcard.util.SrsUtil;
import com.jlas.flashcard.util.SrsUtil.Schedule;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Lịch ôn SRS (SM-2, xem {@link SrsUtil}) cho flashcard: chấm thẻ ở mọi màn (học, quiz, đánh dấu nhớ, Ôn hôm nay),
 * hàng ôn gộp mọi bộ thẻ và thống kê (heatmap, dự báo, tỉ lệ nhớ).
 */
@Service
@RequiredArgsConstructor
@Transactional
public class FlashcardSrsService {
    public static final int DEFAULT_DUE_LIMIT = 50;
    public static final int MAX_DUE_LIMIT = 200;
    public static final int FORECAST_DAYS = 7;
    public static final int HEATMAP_DAYS = 365;
    private static final Set<String> SOURCES = Set.of("STUDY", "QUIZ", "MARK", "DUE");

    private final FlashcardCardProgressRepository progress;
    private final FlashcardReviewLogStore reviewLog;
    private final FlashcardStudyAccess studyAccess;
    private final Clock clock;

    /** Áp SM-2 lên bản ghi tiến độ và ghi nhật ký; người gọi tự lưu {@code p}. */
    public Schedule record(FlashcardCardProgress p, int quality, String source) {
        LocalDateTime now = LocalDateTime.now(clock);
        int previousInterval = Optional.ofNullable(p.getIntervalDays()).orElse(0);
        Schedule schedule = SrsUtil.review(state(p), quality, now);
        applySchedule(p, schedule, SrsUtil.clampQuality(quality), now);
        log(p, SrsUtil.clampQuality(quality), previousInterval, schedule, source, now);
        return schedule;
    }

    /**
     * Người học tự đánh dấu "Nhớ"/"Chưa nhớ" (danh sách thẻ, warm-up). "Chưa nhớ" là một lần quên (q = 1);
     * "Nhớ" là q = 5 và đưa thẻ lên đã thuộc ngay như UI cũ, lần ôn sau không sớm hơn 6 ngày.
     */
    public void mark(FlashcardCardProgress p, boolean remembered) {
        if (!remembered) { record(p, 1, "MARK"); return; }
        record(p, 5, "MARK");
        if (!"MASTERED".equals(p.getStatus())) {
            int interval = Math.max(6, Optional.ofNullable(p.getIntervalDays()).orElse(0));
            p.setStatus("MASTERED");
            p.setRepetitionCount(Math.max(SrsUtil.MASTERED_REPETITIONS, Optional.ofNullable(p.getRepetitionCount()).orElse(0)));
            p.setIntervalDays(interval);
            p.setNextReviewAt(LocalDate.now(clock).plusDays(interval).atStartOfDay());
        }
    }

    @Transactional(readOnly = true)
    public DueQueueDto due(Long userId, Integer limit) {
        Long user = login(userId);
        LocalDateTime now = LocalDateTime.now(clock);
        int size = limit == null ? DEFAULT_DUE_LIMIT : Math.max(1, Math.min(MAX_DUE_LIMIT, limit));
        List<DueCardDto> cards = progress.findDue(user, now, PageRequest.of(0, size)).stream().map(p -> dueCard(p, now)).toList();
        return DueQueueDto.builder().dueCount(progress.countDue(user, now)).cards(cards).build();
    }

    public DueReviewResultDto reviewDue(DueReviewRequest request, Long userId) {
        Long user = login(userId);
        if (request == null || request.getCardId() == null || request.getQuality() == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cần cardId và quality");
        FlashcardCardProgress p = progress.findById(new FlashcardCardProgressId(user, request.getCardId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Thẻ chưa có trong lịch ôn"));
        studyAccess.studyable(p.getCard().getSet().getId(), user);
        Schedule schedule = record(p, request.getQuality(), "DUE");
        progress.save(p);
        return DueReviewResultDto.builder().cardId(request.getCardId()).memoryStatus(FlashcardMapper.memoryStatus(p))
                .intervalDays(schedule.intervalDays()).nextReviewAt(schedule.nextReviewAt()).scheduled(schedule.scheduled())
                .dueCount(progress.countDue(user, LocalDateTime.now(clock))).build();
    }

    @Transactional(readOnly = true)
    public SrsStatsDto stats(Long userId) {
        Long user = login(userId);
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDate today = now.toLocalDate();

        Map<String, Long> byStatus = new HashMap<>();
        for (Object[] row : progress.countByStatus(user)) byStatus.put(String.valueOf(row[0]), ((Number) row[1]).longValue());

        long dueNow = progress.countDue(user, now);
        Map<LocalDate, Long> upcoming = new TreeMap<>();
        for (LocalDateTime at : progress.findUpcomingDueTimes(user, now, today.plusDays(FORECAST_DAYS).atStartOfDay()))
            upcoming.merge(at.toLocalDate(), 1L, Long::sum);
        List<DayCountDto> forecast = new ArrayList<>();
        for (int i = 0; i < FORECAST_DAYS; i++) {
            LocalDate day = today.plusDays(i);
            // Hôm nay = thẻ đã đến hạn + thẻ sẽ tới hạn trước nửa đêm.
            long count = upcoming.getOrDefault(day, 0L) + (i == 0 ? dueNow : 0);
            forecast.add(DayCountDto.builder().date(day).count(count).build());
        }

        Map<LocalDate, Long> daily = reviewLog.dailyCounts(user, today.minusDays(HEATMAP_DAYS - 1L));
        List<DayCountDto> heatmap = daily.entrySet().stream()
                .map(e -> DayCountDto.builder().date(e.getKey()).count(e.getValue()).build()).toList();
        Totals totals = reviewLog.scheduledTotals(user, now.minusDays(30));
        Double retention = totals.reviews() == 0 ? null : Math.round(totals.passed() * 1000.0 / totals.reviews()) / 10.0;

        return SrsStatsDto.builder().dueNow(dueNow).learning(byStatus.getOrDefault("LEARNING", 0L))
                .mastered(byStatus.getOrDefault("MASTERED", 0L)).reviewsToday(daily.getOrDefault(today, 0L))
                .streakDays(streak(daily.keySet(), today)).retention30d(retention).reviews30d(totals.reviews())
                .forecast(forecast).heatmap(heatmap).build();
    }

    /** Số ngày học liên tiếp tính đến hôm nay; chưa học hôm nay thì chuỗi vẫn tính tới hôm qua. */
    static int streak(Set<LocalDate> days, LocalDate today) {
        LocalDate day = days.contains(today) ? today : today.minusDays(1);
        int count = 0;
        while (days.contains(day)) { count++; day = day.minusDays(1); }
        return count;
    }

    private DueCardDto dueCard(FlashcardCardProgress p, LocalDateTime now) {
        FlashcardCard card = p.getCard();
        return DueCardDto.builder().card(FlashcardMapper.card(card, p)).setId(card.getSet().getId())
                .setTitle(card.getSet().getTitle()).intervalDays(Optional.ofNullable(p.getIntervalDays()).orElse(0))
                .nextReviewAt(p.getNextReviewAt()).intervalPreview(SrsUtil.preview(state(p), now)).build();
    }

    private static SrsUtil.State state(FlashcardCardProgress p) {
        return SrsUtil.State.of(p.getRepetitionCount(), p.getIntervalDays(), p.getEasinessFactor(), p.getNextReviewAt());
    }

    private static void applySchedule(FlashcardCardProgress p, Schedule schedule, int quality, LocalDateTime now) {
        p.setStatus(schedule.status());
        p.setRepetitionCount(schedule.repetitions());
        p.setIntervalDays(schedule.intervalDays());
        p.setEasinessFactor(schedule.easiness());
        p.setNextReviewAt(schedule.nextReviewAt());
        p.setReviewCount(Optional.ofNullable(p.getReviewCount()).orElse(0) + 1);
        p.setLastQuality((byte) quality);
        p.setLastReviewedAt(now);
    }

    private void log(FlashcardCardProgress p, int quality, int previousInterval, Schedule schedule, String source, LocalDateTime now) {
        if (p.getId() == null || p.getId().getUserId() == null) return;
        Long setId = p.getCard() != null && p.getCard().getSet() != null ? p.getCard().getSet().getId() : null;
        reviewLog.append(new FlashcardReviewLogStore.Entry(p.getId().getUserId(), p.getId().getCardId(), setId, quality,
                previousInterval, schedule.intervalDays(), schedule.scheduled(),
                SOURCES.contains(source) ? source : "STUDY", now));
    }

    private static Long login(Long userId) {
        if (userId == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Vui lòng đăng nhập");
        return userId;
    }
}
