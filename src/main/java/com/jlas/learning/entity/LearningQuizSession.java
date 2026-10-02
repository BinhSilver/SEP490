package com.jlas.learning.entity;

import com.jlas.auth.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * [JLAS-W0108] Hợp đồng đề thi bất biến: khi phát quiz, server chụp lại toàn bộ câu hỏi + đáp án vào
 * một bản ghi session; lúc nộp chỉ chấm theo snapshot này (mẫu số = toàn bộ câu đã phát), không theo
 * tập câu trả lời của client và không phụ thuộc ngân hàng đổi sau khi phát. Nộp hợp lệ chuyển trạng
 * thái đúng một lần trong transaction; nộp lại (retry) trả kết quả đã lưu, không tốn thêm lượt.
 * Table: learning_quiz_session
 */
@Entity
@Table(name = "learning_quiz_session")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LearningQuizSession {

    /** Id của session, đồng thời là attemptId trả về client khi phát đề. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_quiz_session_user"))
    private User user;

    /** GATE / LESSON / GRAMMAR / FLASHCARD. */
    @Column(name = "quiz_type", nullable = false, length = 20)
    private String quizType;

    /** null cho GATE/GRAMMAR/FLASHCARD; id bài học cho LESSON quiz. */
    @Column(name = "lesson_id")
    private Long lessonId;

    /** null cho LESSON; id lộ trình cho GATE/FLASHCARD quiz. */
    @Column(name = "path_id")
    private Long pathId;

    /** [JLAS-W0108] P4 — anchor hoạt động riêng cho GRAMMAR quiz (grammarPointId). null cho các loại khác. */
    @Column(name = "activity_id")
    private Long activityId;

    /** [JLAS-W0193] Thứ tự batch micro-check (0-based); null cho quiz legacy. */
    @Column(name = "batch_index")
    private Integer batchIndex;

    /** Snapshot version của lộ trình lúc phát GATE quiz; dùng để từ chối nộp khi lộ trình đã đổi. */
    @Column(name = "path_version")
    private Integer pathVersion;

    /** ISSUED / SUBMITTED. */
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = "ISSUED";

    /** JSON mảng câu hỏi đã phát (không chứa đáp án đúng). */
    @Column(name = "questions_json", columnDefinition = "TEXT", nullable = false)
    private String questionsJson;

    /** JSON map questionId -> {correctOption, explanation}; đáp án bất biến khi chấm. */
    @Column(name = "answer_key_json", columnDefinition = "TEXT", nullable = false)
    private String answerKeyJson;

    /** Mẫu số: tổng số câu đã phát (kể cả câu client bỏ trống vẫn tính là sai). */
    @Column(name = "question_count", nullable = false)
    private Integer questionCount;

    @Column(name = "correct_count")
    private Integer correctCount;

    @Column(name = "score_percent")
    private Integer scorePercent;

    @Column(name = "passed")
    private Boolean passed;

    /** JSON kết quả đã chấm (LessonQuizResult) để trả lại nguyên vẹn khi nộp lại. */
    @Column(name = "result_json", columnDefinition = "TEXT")
    private String resultJson;

    @Column(name = "issued_at", nullable = false)
    private LocalDateTime issuedAt;

    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    @PrePersist
    protected void onCreate() {
        if (issuedAt == null) {
            issuedAt = LocalDateTime.now();
        }
    }
}
