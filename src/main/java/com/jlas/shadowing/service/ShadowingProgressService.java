package com.jlas.shadowing.service;

import com.jlas.shadowing.dto.ShadowingProgressDto;
import com.jlas.shadowing.dto.ShadowingProgressDto.WeekScore;
import com.jlas.shadowing.repository.ShadowingProgressRepository;
import com.jlas.shadowing.repository.ShadowingProgressRepository.DailyScore;
import com.jlas.shadowing.repository.ShadowingProgressRepository.ListeningTotals;
import com.jlas.shadowing.repository.ShadowingProgressRepository.PronunciationTotals;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ShadowingProgressService {
    private static final int WEEKS = 8;
    /** A sentence counts as mastered once any attempt reaches this score. */
    private static final double WEAK_SCORE_THRESHOLD = 70;

    private final ShadowingProgressRepository progress;

    @Transactional(readOnly = true)
    public ShadowingProgressDto forUser(long userId) {
        LocalDate today = LocalDate.now();
        LocalDate firstWeek = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(WEEKS - 1);
        PronunciationTotals allTime = progress.pronunciationTotals(userId, null);
        PronunciationTotals last30 = progress.pronunciationTotals(userId, today.minusDays(29).atStartOfDay());
        ListeningTotals listening = progress.listeningTotals(userId);

        return new ShadowingProgressDto(
                progress.studiedVideos(userId),
                allTime.sentences(),
                allTime.attempts(),
                round(last30.averageScore()),
                listening.completedSessions(),
                listening.questions() > 0 ? round(100.0 * listening.correct() / listening.questions()) : null,
                weeklyScores(progress.dailyScores(userId, firstWeek.atStartOfDay()), firstWeek),
                progress.weakSentences(userId, today.minusDays(29).atStartOfDay(), WEAK_SCORE_THRESHOLD, 5),
                progress.recentVideos(userId, 6));
    }

    private static List<WeekScore> weeklyScores(List<DailyScore> days, LocalDate firstWeek) {
        double[] sums = new double[WEEKS];
        int[] counts = new int[WEEKS];
        for (DailyScore day : days) {
            int week = (int) (ChronoUnit.DAYS.between(firstWeek, day.day()) / 7);
            if (week < 0 || week >= WEEKS) continue;
            sums[week] += day.scoreSum();
            counts[week] += day.attempts();
        }
        List<WeekScore> weeks = new ArrayList<>(WEEKS);
        for (int i = 0; i < WEEKS; i++) {
            weeks.add(new WeekScore(firstWeek.plusWeeks(i), counts[i] > 0 ? round(sums[i] / counts[i]) : null, counts[i]));
        }
        return weeks;
    }

    private static Double round(Double value) {
        return value == null ? null : Math.round(value * 10) / 10.0;
    }
}
