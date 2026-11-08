package com.jlas.flashcard.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** API contract ported from the legacy flashcard UI. */
public final class FlashcardDtos {
    private FlashcardDtos() { }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class CardDto {
        private Long id; private String front; private String back; private String kanji; private String hiragana; private String reading;
        private String meaning; private String hanViet; private String image; private String jlptLevel;
        private String wordType; private String exampleJa; private String exampleVi; private int position;
        private boolean starred; private String memoryStatus;
    }
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class SetSummaryDto {
        private Long id; private String title; private String name; private String description; private String thumbnail;
        private String visibility; private String moderationStatus; private Long ownerId; private String ownerName;
        private Long folderId; private String folderName; private boolean draft;
        private long totalCards; private long cardCount; private long studyCount; private Double avgRating; private long ratingCount;
        private long commentCount; private boolean ownedByMe; private boolean favorited; private Integer myRating; private LocalDateTime createdAt; private LocalDateTime updatedAt;
        /** Lần gần nhất người dùng mở bộ này; chỉ có ở tab Lịch sử. */
        private LocalDateTime viewedAt;
    }
    @Data @NoArgsConstructor @AllArgsConstructor
    public static class SetDetailDto extends SetSummaryDto { private List<CardDto> cards; }
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class PageResponse<T> {
        private List<T> content; private int page; private int size; private long totalElements;
        private int totalPages; private boolean first; private boolean last;
    }
    @Data @NoArgsConstructor @AllArgsConstructor
    public static class CardInput {
        private Long id; @NotBlank(message = "Từ vựng (Kanji hoặc Kana) không được để trống") @Size(max = 500) private String front; @Size(max = 500) private String back;
        @Size(max = 255) private String reading; // optional: only the word and its meaning are required
        @NotBlank(message = "Nghĩa không được để trống") @Size(max = 500) private String meaning;
        @Size(max = 255) private String hanViet; @Size(max = 500) private String image;
        private String jlptLevel; private String wordType; private String exampleJa; private String exampleVi; private Integer position;
    }
    @Data @NoArgsConstructor @AllArgsConstructor
    public static class SetRequest {
        @NotBlank(message = "Tên bộ thẻ không được để trống") @Size(max = 100) private String title; @Size(max = 500) private String description;
        private String thumbnail; private String visibility; private Long folderId; @Valid private List<CardInput> cards;
    }
    @Data @NoArgsConstructor @AllArgsConstructor
    public static class DraftRequest {
        @Size(max = 100) private String title; @Size(max = 500) private String description;
        @Size(max = 500) private String thumbnail; private String visibility; private Long folderId;
        private List<CardInput> cards;
    }
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class FolderDto { private Long id; private String name; private long setCount; }
    @Data @NoArgsConstructor @AllArgsConstructor
    public static class FolderRequest { @NotBlank @Size(max = 100) private String name; }
    @Data @NoArgsConstructor @AllArgsConstructor
    public static class MoveFolderRequest { private Long folderId; }
    @Data @NoArgsConstructor @AllArgsConstructor
    public static class ImportRequest { @NotBlank private String title; private String description; private String visibility; @NotBlank private String text; }
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ParsedLineDto { private String front; private String reading; private String back; private String hanViet; private String raw; private boolean valid; }
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ImportHanVietResponse { private String text; private List<ParsedLineDto> lines; }
    @Data @NoArgsConstructor @AllArgsConstructor public static class ImportCardsRequest { @NotBlank @Size(max = 12000) private String text; }
    @Data @NoArgsConstructor @AllArgsConstructor public static class EnrichRequest { @NotBlank private String kanji; }
    @Data @NoArgsConstructor @AllArgsConstructor public static class EnrichBatchRequest { private List<String> kanjis; }
    @Data @NoArgsConstructor @AllArgsConstructor public static class BatchHanVietRequest { private List<Long> cardIds; }
    @Data @Builder @NoArgsConstructor @AllArgsConstructor public static class EnrichResponse { private String front; private String back; private String reading; private String meaning; private String hanViet; }
    @Data @NoArgsConstructor @AllArgsConstructor public static class ReviewRequest { private Long setId; private Long cardId; private Integer quality; }
    /** Thẻ trong hàng "Ôn hôm nay"; intervalPreview: mức chấm 0–5 → số ngày tới lần ôn kế tiếp. */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor public static class DueCardDto { private CardDto card; private Long setId; private String setTitle; private int intervalDays; private LocalDateTime nextReviewAt; private Map<Integer, Integer> intervalPreview; }
    @Data @Builder @NoArgsConstructor @AllArgsConstructor public static class DueQueueDto { private long dueCount; private List<DueCardDto> cards; }
    @Data @NoArgsConstructor @AllArgsConstructor public static class DueReviewRequest { private Long cardId; private Integer quality; }
    @Data @Builder @NoArgsConstructor @AllArgsConstructor public static class DueReviewResultDto { private Long cardId; private String memoryStatus; private int intervalDays; private LocalDateTime nextReviewAt; private boolean scheduled; private long dueCount; }
    @Data @Builder @NoArgsConstructor @AllArgsConstructor public static class DayCountDto { private LocalDate date; private long count; }
    @Data @Builder @NoArgsConstructor @AllArgsConstructor public static class SrsStatsDto {
        private long dueNow; private long learning; private long mastered; private long reviewsToday; private int streakDays;
        /** Tỉ lệ nhớ (%) của các lượt ôn đúng hạn trong 30 ngày; null khi chưa có lượt nào. */
        private Double retention30d; private long reviews30d;
        private List<DayCountDto> forecast; private List<DayCountDto> heatmap;
    }
    @Data @Builder @NoArgsConstructor @AllArgsConstructor public static class StudySessionDto { private Long setId; private String title; private String mode; private List<CardDto> cards; private int total; private int learning; private int mastered; }
    @Data @Builder @NoArgsConstructor @AllArgsConstructor public static class StudySummaryDto { private int remembered; private int notRemembered; private int total; private int starred; private List<CardDto> notRememberedCards; }
    @Data @NoArgsConstructor @AllArgsConstructor public static class StartQuizRequest { private Long setId; private List<Long> selectedCardIds; private boolean wrongOnly; /** KANJI/HIRAGANA/MEANING/HAN_VIET; mặc định Kanji → Nghĩa. */ private String questionField; private String answerField; }
    @Data @Builder @NoArgsConstructor @AllArgsConstructor public static class QuizQuestionDto { private Long sessionId; private Long cardId; private String question; private List<OptionDto> options; private int questionIndex; private int totalQuestions; private String reading; private String meaning; private String hanViet; }
    @Data @Builder @NoArgsConstructor @AllArgsConstructor public static class OptionDto { private Long cardId; private String label; private String text; }
    @Data @NoArgsConstructor @AllArgsConstructor public static class AnswerRequest { private Long sessionId; private Long cardId; private Long chosenCardId; }
    @Data @Builder @NoArgsConstructor @AllArgsConstructor public static class AnswerResultDto { private boolean correct; private Long correctCardId; private String correctText; private int currentCorrect; private int currentWrong; }
    @Data @Builder @NoArgsConstructor @AllArgsConstructor public static class QuizSummaryDto { private Long sessionId; private int total; private int correct; private int wrong; private double percentage; private int durationSeconds; private double avgSecondsPerQuestion; private List<CardDto> wrongCards; }
    @Data @Builder @NoArgsConstructor @AllArgsConstructor public static class CommentDto { private Long id; private Long userId; private String displayName; private String content; private Integer rating; private boolean ownedByMe; private LocalDateTime createdAt; /** Ảnh đại diện + khung avatar + màu tên người bình luận (khung/màu mua ở Cửa hàng; null = không có). */ private String avatarUrl; private String avatarFrame; private String nameStyle; }
    @Data @NoArgsConstructor @AllArgsConstructor public static class CommentRequest { @NotBlank @Size(max = 2000) private String content; private Integer rating; }
    @Data @NoArgsConstructor @AllArgsConstructor public static class SetRatingRequest { private Integer rating; }
    @Data @Builder @NoArgsConstructor @AllArgsConstructor public static class FavoriteResponse { private boolean favorited; }
    @Data @NoArgsConstructor @AllArgsConstructor public static class ProgressRequest { private Boolean starred; private String memoryStatus; }
    @Data @Builder @NoArgsConstructor @AllArgsConstructor public static class SyncPreviewResponse { private long newCardCount; private String originalTitle; }
    @Data @Builder @NoArgsConstructor @AllArgsConstructor public static class SyncResponse { private long addedCount; }
    @Data @Builder @NoArgsConstructor @AllArgsConstructor public static class UploadResponse { private String url; private String filename; }
    @Data @Builder @NoArgsConstructor @AllArgsConstructor public static class ExportFileDto { private String filename; private String contentType; private byte[] content; }
    @Data @Builder @NoArgsConstructor @AllArgsConstructor public static class ImportFileResponse { private String text; private int lineCount; }
    @Data @NoArgsConstructor @AllArgsConstructor public static class AiRequest { private Long cardId; private String term; private String type; private String context; private String message; }
    @Data @Builder @NoArgsConstructor @AllArgsConstructor public static class AiResponse { private String type; private String term; private String reading; private String meaning; private String hanViet; private String reply; private boolean mock; }
}
