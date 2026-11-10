package com.jlas.flashcard.dto;

import com.jlas.flashcard.speedrecall.SpeedRecallMode;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;

public final class SpeedRecallDtos {
    private SpeedRecallDtos() {}
    public record CreateRequest(
        @NotBlank @Pattern(regexp="[0-9a-fA-F-]{36}") String requestId,
        @NotBlank @Pattern(regexp="ALL|LEARNING|STARRED|CUSTOM|RANDOM") String filter,
        @Min(1) int count, boolean autoSubmit,
        @Pattern(regexp=SpeedRecallMode.PATTERN, message="Chế độ luyện không hợp lệ") String mode,
        List<@NotNull @Positive Long> selectedCardIds,
        /** Seconds per word (SpeedRecallRules.TIME_LIMIT_CHOICES); null keeps the automatic time. */
        Integer timeLimitSeconds) {}
    public record RetryRequest(@NotBlank @Pattern(regexp="[0-9a-fA-F-]{36}") String requestId) {}
    public record NextRequest(@Min(0) int questionIndex) {}
    public record AnswerRequest(@NotNull @Positive Long questionId,
        @NotBlank @Pattern(regexp="[0-9a-fA-F-]{36}") String requestId,
        @NotNull @Size(max=500) String userAnswer) {}
    public record Options(Long setId, String title, int totalCards, int eligible, int excluded,
                          int learning, int starred, String mode,
                          /** Eligible cards per mode (all cards filter), so the picker can hide empty choices. */
                          java.util.Map<String, Integer> modeCounts, List<OptionCard> cards) {}
    public record OptionCard(Long id, String kanji, String hiragana, String meaning, String hanViet,
                             boolean eligible, boolean learning, String unavailableReason) {}
    public record Question(Long id, int index, String text, String expectedAnswer,
                           int timeLimitMs, Instant deadlineAt) {}
    public record Answer(Long questionId, Long cardId, String question, String reading, String meaning,
                         String userAnswer, String result, Integer responseMs, int scoreAwarded,
                         String expectedAnswer) {}
    public record Stats(int total, int correct, int wrong, int timeout, int score, int streak,
                        int bestStreak, double accuracy, long averageResponseMs) {}
    public record View(Long id, Long setId, String title, String state, String pausedFrom,
                       int currentQuestionIndex, String filter, boolean autoSubmit,
                       Instant serverNow, Instant feedbackUntil, Long pausedRemainingMs,
                       Question question, Answer feedback, Stats stats, List<Answer> results,
                       Integer personalBestScore, String mode) {}
    public record HistoryItem(Long id, String state, Instant startedAt, String filter, boolean autoSubmit,
                              String contentVersion, String rulesVersion, Stats stats, String mode) {}
    public record History(List<HistoryItem> content, int page, int totalPages, long totalElements) {}
}
