package com.jlas.learning.service;

import com.jlas.learning.dto.CreateLearningPathResult;
import com.jlas.learning.dto.LearningPathResponse;
import com.jlas.learning.dto.LessonQuizResponse;
import com.jlas.learning.dto.LessonQuizResult;
import com.jlas.learning.dto.LessonQuizSubmitRequest;

/** Orchestrates two-phase learning path creation and reading. */
public interface LearningService {
    /** Creates a path for a finalized assessment attempt, or returns the existing one on retry. */
    CreateLearningPathResult create(Long userId, Long assessmentAttemptId);

    /**
     * [JLAS-W0108] Creates a from-zero path from a completed survey (no Smart Test needed), or
     * returns the existing one on retry. The path warms up with the kana foundation before the goal.
     */
    CreateLearningPathResult createFromSurvey(Long userId);

    /** Returns the user's active path; empty state is NOT_FOUND. */
    LearningPathResponse current(Long userId);

    /** Marks a lesson COMPLETED for the user, creating the progress record if needed. */
    void completeLesson(Long userId, Long lessonId);

    /**
     * [JLAS-W0108] P2 — đánh dấu hoàn thành nội dung một activity (item) trong lộ trình.
     * Độc lập source lesson: hai item dùng chung bài, hoàn thành một item không hoàn thành item kia.
     */
    void completeItem(Long userId, Long itemId);

    /** [JLAS-W0108][MP06] Đánh dấu một activity đã được mở/bắt đầu (NOT_STARTED → IN_PROGRESS),
     * để "Tiếp tục học" trỏ đúng chỗ người học đang dở thay vì đoán theo thứ tự. */
    void startItem(Long userId, Long itemId);

    /** [JLAS-W0113] Returns the questions for a lesson quiz (answers withheld). */
    LessonQuizResponse getLessonQuiz(Long userId, Long lessonId);

    /** [JLAS-W0113] Grades a submitted lesson quiz against the answer key; on pass marks the lesson complete. */
    LessonQuizResult submitLessonQuiz(Long userId, Long lessonId, LessonQuizSubmitRequest request);

    /** [JLAS-W0113] Returns the warm-up gate quiz: questions aggregated from the weak tags of the WARM_UP phase. */
    LessonQuizResponse getGateQuiz(Long userId);

    /** [JLAS-W0113] Grades the gate quiz; on pass unlocks the GOAL_LEARNING phase (records gate_passed_at). */
    LessonQuizResult submitGateQuiz(Long userId, LessonQuizSubmitRequest request);

    /** [JLAS-W0108] Lấy bài final cuối chặng (5 kỹ năng, gộp 1 phiên); chỉ phát khi chặng ACTIVE và
     *  mọi item goal-learning của chặng đã STUDIED. */
    LessonQuizResponse getStageFinalQuiz(Long userId, Long stageId);

    /**
     * [JLAS-W0108] P4 — warm-up grammar quiz: retest đúng mẫu ngữ pháp đã sai (5 câu khác câu đầu vào,
     * cùng mẫu). Câu từ ngân hàng gắn {@code grammarPointId}; thiếu câu -> QUIZ_NOT_READY (BLOCKED_CONTENT).
     */
    LessonQuizResponse getGrammarQuiz(Long userId, Long grammarPointId);

    /** [JLAS-W0108] P4 — chấm warm-up grammar quiz; đạt 4/5 ghi assessment PASSED trên item mẫu ngữ pháp. */
    LessonQuizResult submitGrammarQuiz(Long userId, Long grammarPointId, LessonQuizSubmitRequest request);

    /** [JLAS-W0108] P4/D3 — flashcard quiz: kiểm tra toàn bộ membership (từ/Kanji sai) bằng MCQ chọn nghĩa. */
    LessonQuizResponse getFlashcardQuiz(Long userId);

    /** [JLAS-W0108] P4/D3 — chấm flashcard quiz; đúng hết membership ghi assessment PASSED trên item từ/Kanji. */
    LessonQuizResult submitFlashcardQuiz(Long userId, LessonQuizSubmitRequest request);
}
