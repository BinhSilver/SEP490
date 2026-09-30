package com.jlas.shadowing.dto;

import com.jlas.shadowing.repository.ShadowingProgressRepository.RecentVideo;
import com.jlas.shadowing.repository.ShadowingProgressRepository.WeakSentence;

import java.time.LocalDate;
import java.util.List;

/** Learner's shadowing progress: headline numbers, weekly pronunciation scores and what to revisit. */
public record ShadowingProgressDto(
        int studiedVideos,
        int practicedSentences,
        int pronunciationAttempts,
        /** Mean pronunciation score over the last 30 days, or null without attempts. */
        Double averageScore30d,
        int listeningSessions,
        /** Share of listening questions answered correctly, 0–100, or null without completed sessions. */
        Double listeningAccuracy,
        List<WeekScore> weeks,
        List<WeakSentence> weakSentences,
        List<RecentVideo> recentVideos) {

    /** A Monday-to-Sunday week; averageScore is null when nothing was scored that week. */
    public record WeekScore(LocalDate weekStart, Double averageScore, int attempts) {}
}
