package com.jlas.learning.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * Quiz pages that stay with the textbook lessons: the per-lesson quiz (W0113) and the grammar quiz. The old path's
 * gate, flashcard and stage-final quizzes are gone with the old path (owner 07/11); their addresses redirect.
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

    /** Grammar quiz: retest one grammar point (5 questions, pass 4/5). */
    @GetMapping("/learning/grammar/{grammarPointId}/quiz")
    public String grammarQuiz(@PathVariable Long grammarPointId, Model model) {
        model.addAttribute("quizMode", "grammar");
        model.addAttribute("quizGrammarPointId", grammarPointId);
        return "learning/lesson-quiz";
    }
}
