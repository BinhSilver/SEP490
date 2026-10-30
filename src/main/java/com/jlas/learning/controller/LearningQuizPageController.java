package com.jlas.learning.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * Serves the quiz pages of the learning path: the per-lesson quiz (W0113) and the warm-up
 * gate quiz that unlocks the goal-learning phase. Both reuse the same template + script,
 * switched by {@code quizMode}.
 */
@Controller
public class LearningQuizPageController {

    /** Lesson quiz: questions are drawn from the lesson's knowledge tag. */
    @GetMapping("/learning/lesson/{lessonId}/quiz")
    public String lessonQuiz(@PathVariable Long lessonId, Model model) {
        model.addAttribute("quizMode", "lesson");
        model.addAttribute("quizLessonId", lessonId);
        return "learning/lesson-quiz";
    }

    /** Warm-up gate quiz: questions are aggregated from the weak tags of the WARM_UP phase. */
    @GetMapping("/learning/path/gate-quiz")
    public String gateQuiz(Model model) {
        model.addAttribute("quizMode", "gate");
        return "learning/lesson-quiz";
    }

    /** [JLAS-W0108] P4 — warm-up grammar quiz: retest đúng mẫu ngữ pháp đã sai (5 câu, đạt 4/5). */
    @GetMapping("/learning/grammar/{grammarPointId}/quiz")
    public String grammarQuiz(@PathVariable Long grammarPointId, Model model) {
        model.addAttribute("quizMode", "grammar");
        model.addAttribute("quizGrammarPointId", grammarPointId);
        return "learning/lesson-quiz";
    }

    /** [JLAS-W0108] P4/D3 — flashcard quiz: MCQ chọn nghĩa toàn bộ từ/Kanji sai, đúng hết mới đạt. */
    @GetMapping("/learning/path/flashcard-quiz")
    public String flashcardQuiz(Model model) {
        model.addAttribute("quizMode", "flashcard");
        return "learning/lesson-quiz";
    }

    /** [JLAS-W0108] Bài final cuối chặng (5 kỹ năng, gộp 1 phiên). */
    @GetMapping("/learning/stages/{stageId}/final-quiz")
    public String stageFinalQuiz(@PathVariable Long stageId, Model model) {
        model.addAttribute("quizMode", "stage-final");
        model.addAttribute("quizStageId", stageId);
        return "learning/lesson-quiz";
    }
}
