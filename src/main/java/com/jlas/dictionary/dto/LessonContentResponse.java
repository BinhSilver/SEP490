package com.jlas.dictionary.dto;

import lombok.Builder;
import lombok.Data;
import java.util.List;

/**
 * Nội dung đầy đủ một bài học: Kanji xuất hiện trong từ vựng, từ vựng kèm audio,
 * câu ví dụ kèm audio, mẫu ngữ pháp, hội thoại kèm vai và cụm tách nghĩa.
 */
@Data
@Builder
public class LessonContentResponse {
    private Long id;
    private String title;
    private String description;
    private String lessonType;
    private String skill;
    private String status;
    private Integer orderIndex;
    private Integer estimatedMinutes;
    private Long unitId;
    private String unitTitle;

    /**
     * Ngữ cảnh giáo trình: trang biên soạn cần textbookId để dựng breadcrumb và textbookStatus để khoá
     * thao tác khi giáo trình đã lưu trữ (backend cũng chặn bằng 409, đây chỉ là để báo trước).
     */
    private Long textbookId;
    private String textbookTitle;
    private String textbookStatus;
    private String knowledgeTagCode;
    private String knowledgeTagName;
    private List<KanjiResponse> kanjis;
    private List<LessonVocabularyResponse> vocabularies;
    private List<ExampleSentenceResponse> exampleSentences;
    /**
     * Mục đọc (content_item READING): với giáo trình bảng chữ cái là từng chữ kana (textJa = kana,
     * textVi = romaji); với bài N5/N4 là đoạn chú thích. Dùng chung DTO câu ví dụ vì cùng cấu trúc.
     */
    private List<ExampleSentenceResponse> readingItems;
    /**
     * Mục nghe (content_item LISTENING) KHÔNG phải lời hội thoại — lời hội thoại đã nằm ở dialogueLines.
     * Thường là transcript của một bài nghe, hoặc một mục chỉ mang file audio (textJa = null).
     */
    private List<ExampleSentenceResponse> listeningItems;
    private List<GrammarPointResponse> grammarPoints;
    private List<DialogueRoleResponse> dialogueRoles;
    private List<DialogueLineResponse> dialogueLines;

    /**
     * Câu hỏi luyện tập của bài, đọc từ quiz của bài (assessment_quiz, chỉ đọc). Trang học hiện chúng thành thẻ
     * chọn đáp án và báo đúng/sai ngay — không lưu điểm; bài kiểm tra chấm điểm vẫn đi qua luồng quiz riêng.
     */
    private List<PracticeQuestion> practiceQuestions;
    /** Bài có quiz LESSON đã xuất bản → trang học hiện nút "Làm bài kiểm tra" (/learning/lesson/{id}/quiz). */
    private Boolean quizAvailable;

    /** [JLAS2-17] Nguồn nội dung của bài (content_source.source_code của LESSON, vd VNJPCLUB); null = nội dung nhóm biên soạn. */
    private String contentSource;

    @Data
    @Builder
    public static class PracticeQuestion {
        private Long id;
        private Integer orderIndex;
        private String questionText;
        /** Bản dịch đề (context_json.question_translation); có thể null. */
        private String translation;
        /** Phương án theo thứ tự hiển thị, đánh số từ 1. */
        private List<String> options;
        /** Số thứ tự phương án đúng (1-based) khi câu có đúng một đáp án; null nếu nhiều đáp án hoặc không chắc chắn. */
        private Integer correctOption;
        /** Mọi phương án đúng (1-based, nằm trong danh sách phương án); rỗng nếu nguồn không có đáp án chắc chắn. */
        private List<Integer> correctOptions;
        /** Đáp án dạng chữ cho câu điền (không có phương án); có thể null. */
        private String answerText;
        /**
         * Dạng "cách đọc chữ Hán": cụm chữ Hán (kèm okurigana) trong đề mà từ điển có cách đọc đúng bằng đáp án —
         * trang học gạch chân cụm này. Null khi không xác định chắc (trang tự xử lý câu chỉ có một cụm chữ Hán).
         */
        private String targetText;
        private String explanation;
        /** Mã gốc của bài đọc/bài nghe chứa câu (khớp ExampleSentenceResponse.stableCode); có thể null. */
        private String parentCode;
        /** Audio riêng của câu (đường dẫn tương đối trong content.audio.path) và mục nghe mang file đó. */
        private String audioUrl;
        private Long audioContentItemId;
        /** Đoạn văn của câu đọc hiểu (assessment_question.passage_html); có thể null. */
        private String passageHtml;
        /** Ảnh đoạn văn / minh hoạ (assessment_question.image_url): tương đối trong content.audio.path, hoặc URL/đường dẫn tuyệt đối. */
        private String imageUrl;
        /** Mã gốc của đoạn văn (context_json.passage_code); trùng parentCode khi đoạn văn chính là bài đọc chứa câu. */
        private String passageCode;
        /**
         * Mọi ảnh đoạn văn của câu theo thứ tự: image_url rồi context_json.passage_images (đoạn văn nhiều trang),
         * không trùng. Rỗng khi không có ảnh. image_url là ảnh của chính câu hỏi (có trong question_images — câu chỉ có
         * ảnh đề, không có ảnh đoạn văn) thì không tính là ảnh đoạn văn.
         */
        private List<String> passageImages;
        /**
         * Ảnh của chính câu hỏi (tranh để chọn, hình minh hoạ đề): context_json.question_images (mảng) hoặc
         * context_json.question_image (một đường dẫn), theo thứ tự trang nguồn.
         */
        private List<String> questionImages;
        /**
         * [JLAS2-17] Ảnh dùng chung của bài đọc / bài nghe chứa câu (tranh bài nghe, tranh bài tập, hình minh hoạ):
         * context_json.common_images. Mọi câu cùng bài đọc / nghe mang cùng danh sách; trang học hiện một lần ở đầu khối.
         */
        private List<String> commonImages;
    }
}
