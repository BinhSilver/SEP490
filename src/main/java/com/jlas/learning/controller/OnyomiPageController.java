package com.jlas.learning.controller;

import com.jlas.auth.security.UserPrincipal;
import com.jlas.learning.dto.OnyomiFinalView;
import com.jlas.learning.dto.OnyomiLessonStatus;
import com.jlas.learning.dto.OnyomiLessonView;
import com.jlas.learning.dto.OnyomiRulePageView;
import com.jlas.learning.service.OnyomiCourseService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Pages of the Onyomi course (Hán Việt → âm On). A locked lesson or final test sends the learner back
 * to the course page, which explains what to finish first.
 */
@Controller
@RequestMapping("/learning/onyomi")
@RequiredArgsConstructor
public class OnyomiPageController {
    private static final String COURSE = "/learning/onyomi";
    private static final String LOCKED = OnyomiLessonStatus.LOCKED.name();

    private final OnyomiCourseService courseService;

    @GetMapping
    public String course(@RequestParam(value = "locked", required = false) String locked,
                         @AuthenticationPrincipal UserPrincipal user, Model model) {
        model.addAttribute("course", courseService.course(user.userId()));
        model.addAttribute("lockedNotice", locked);
        model.addAttribute("pageTitle", "Lộ trình Onyomi - JLAS");
        model.addAttribute("layoutMode", "app");
        return "learning/onyomi/path";
    }

    @GetMapping("/lessons/{id}")
    public String lesson(@PathVariable("id") Long id, @AuthenticationPrincipal UserPrincipal user, Model model) {
        OnyomiLessonView lesson = courseService.lesson(user.userId(), id).orElse(null);
        if (lesson == null) {
            return "redirect:" + COURSE;
        }
        if (LOCKED.equals(lesson.status())) {
            return "redirect:" + COURSE + "?locked=lesson";
        }
        model.addAttribute("lesson", lesson);
        model.addAttribute("pageTitle", "Bài " + lesson.order() + ": " + lesson.title() + " - Onyomi - JLAS");
        model.addAttribute("layoutMode", "app");
        return "learning/onyomi/lesson";
    }

    @GetMapping("/rules/{id}")
    public String rule(@PathVariable("id") Long id, @AuthenticationPrincipal UserPrincipal user, Model model) {
        OnyomiRulePageView page = courseService.rule(user.userId(), id).orElse(null);
        if (page == null) {
            return "redirect:" + COURSE;
        }
        if (LOCKED.equals(page.lessonStatus())) {
            return "redirect:" + COURSE + "?locked=lesson";
        }
        model.addAttribute("page", page);
        model.addAttribute("pageTitle", page.rule().title() + " - Onyomi - JLAS");
        model.addAttribute("layoutMode", "app");
        return "learning/onyomi/rule";
    }

    @GetMapping("/stages/{unitId}/final")
    public String stageFinal(@PathVariable("unitId") Long unitId, @AuthenticationPrincipal UserPrincipal user,
                             Model model) {
        OnyomiFinalView stage = courseService.stageFinal(user.userId(), unitId).orElse(null);
        if (stage == null || stage.quizId() == null) {
            return "redirect:" + COURSE;
        }
        if (!stage.unlocked()) {
            return "redirect:" + COURSE + "?locked=final";
        }
        model.addAttribute("stage", stage);
        model.addAttribute("pageTitle", "Kiểm tra cuối giai đoạn " + stage.stageOrder() + " - Onyomi - JLAS");
        model.addAttribute("layoutMode", "app");
        return "learning/onyomi/final";
    }

    /** The test itself on its own page; the stage page above only shows the stage and a start button. */
    @GetMapping("/stages/{unitId}/final/test")
    public String stageFinalTest(@PathVariable("unitId") Long unitId, @AuthenticationPrincipal UserPrincipal user,
                                 Model model) {
        OnyomiFinalView stage = courseService.stageFinal(user.userId(), unitId).orElse(null);
        if (stage == null || stage.quizId() == null) {
            return "redirect:" + COURSE;
        }
        if (!stage.unlocked()) {
            return "redirect:" + COURSE + "?locked=final";
        }
        model.addAttribute("stage", stage);
        model.addAttribute("pageTitle", "Làm bài kiểm tra giai đoạn " + stage.stageOrder() + " - Onyomi - JLAS");
        model.addAttribute("layoutMode", "app");
        return "learning/onyomi/final-test";
    }
}
