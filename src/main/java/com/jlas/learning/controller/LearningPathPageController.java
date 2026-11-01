package com.jlas.learning.controller;

import com.jlas.auth.security.UserPrincipal;
import com.jlas.jlptpath.service.JpathEntryRoute;
import com.jlas.jlptpath.service.JpathEntryRouter;
import com.jlas.learning.config.InteractiveLearningV2Properties;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import java.util.List;

/** Serves the authenticated personalized learning-path page. */
@Controller
@RequiredArgsConstructor
public class LearningPathPageController {
    private final InteractiveLearningV2Properties interactiveV2Properties;
    /** [JLAS-W0108] JLPT-goal learners use the topic-based JLPT path instead of this one. */
    private final JpathEntryRouter jpathEntryRouter;

    @GetMapping("/learning/notebook")
    public String weaknessNotebook(Model model) {
        model.addAttribute("layoutMode", "app");
        return "learning/weakness-notebook";
    }

    /** The Smart Test handoff without entry answers and without a signed-in learner. */
    public String learningPath(Model model, Long assessmentAttemptId, String reviewStartLevel, String discrepancyChoice) {
        return learningPath(null, model, assessmentAttemptId, reviewStartLevel, discrepancyChoice, null, null);
    }

    @GetMapping("/learning/path")
    public String learningPath(@AuthenticationPrincipal UserPrincipal principal, Model model,
            @RequestParam(required = false) Long assessmentAttemptId,
            @RequestParam(required = false) String reviewStartLevel,
            @RequestParam(required = false) String discrepancyChoice,
            @RequestParam(required = false) String kanaKnown,
            @RequestParam(required = false) String entryChoice) {
        if (jpathEntryRouter.route(userId(principal)) != JpathEntryRoute.LEGACY) {
            // [JLAS-W0108] The JLPT path confirms a Smart Test result on its own entry page.
            return assessmentAttemptId == null ? "redirect:/learning"
                    : "redirect:/learning/jlpt/confirm?source=SMART_TEST&assessmentAttemptId=" + assessmentAttemptId;
        }
        if (interactiveV2Properties.isEnabled()) {
            if (assessmentAttemptId == null) return "redirect:/learning/path/v2";
            String redirect = "redirect:/learning/path/v2?assessmentAttemptId=" + assessmentAttemptId;
            // Owner 2026-10-25: the answers to the Smart Test entry questions travel with the handoff.
            if (kanaKnown != null && List.of("true", "false").contains(kanaKnown)) redirect += "&kanaKnown=" + kanaKnown;
            if (entryChoice != null && List.of("SOLID", "NOT_SOLID", "STUDY_WEAK_LEVEL", "SKIP_WEAK_LEVEL", "REVIEW_MISTAKES",
                    "SKIP_REVIEW").contains(entryChoice)) redirect += "&entryChoice=" + entryChoice;
            // List.of(...).contains(null) throws, and most Smart Test handoffs carry neither choice.
            if (reviewStartLevel != null && List.of("N5", "N4", "N3").contains(reviewStartLevel)) {
                return redirect + "&reviewStartLevel=" + reviewStartLevel;
            }
            return discrepancyChoice != null
                    && List.of("ACCEPT_RECOMMENDATION", "DECLINE_RECOMMENDATION").contains(discrepancyChoice)
                    ? redirect + "&discrepancyChoice=" + discrepancyChoice : redirect;
        }
        model.addAttribute("layoutMode", "app");
        return "learning/path";
    }

    @GetMapping("/learning/path/v2")
    public String interactiveLearningPath(@AuthenticationPrincipal UserPrincipal principal, Model model) {
        if (jpathEntryRouter.route(userId(principal)) == JpathEntryRoute.JPATH_PAGE) return "redirect:/learning";
        model.addAttribute("layoutMode", "app");
        return "learning/interactive-path";
    }

    /** [JLAS-W0193] Task 8 — the completion popup's "Xem tổng kết chặng" (last package) routes here.
     * Placeholder only: Task 10 builds the real first/latest/best + readiness summary content. */
    @GetMapping("/learning/path/v2/summary")
    public String interactiveLearningPathSummary(Model model) {
        model.addAttribute("layoutMode", "app");
        return "learning/interactive-path-summary";
    }

    @GetMapping("/learning/v2/vocabulary")
    public String interactiveVocabulary(Model model) {
        model.addAttribute("layoutMode", "app");
        return "learning/interactive-study";
    }

    @GetMapping("/learning/v2/kanji")
    public String interactiveKanji(Model model) {
        model.addAttribute("layoutMode", "app");
        return "learning/interactive-kanji-study";
    }

    /** [JLAS-W0108] Hiragana/Katakana foundation warm-up for from-zero learners. */
    @GetMapping("/learning/v2/foundation")
    public String interactiveFoundation(Model model) {
        model.addAttribute("layoutMode", "app");
        return "learning/interactive-foundation-study";
    }

    @GetMapping("/learning/v2/grammar")
    public String interactiveGrammar(Model model) {
        model.addAttribute("layoutMode", "app");
        return "learning/interactive-grammar-study";
    }

    @GetMapping("/learning/v2/dialogue")
    public String interactiveDialogue(Model model) {
        model.addAttribute("layoutMode", "app");
        return "learning/interactive-dialogue-study";
    }

    /** [JLAS-W0193] RC Task 5 — Reading and Listening share one template/JS/CSS bundle (mirrors the shape of
     * Vocabulary/Kanji sharing {@code interactive-study.js}); {@code comprehensionKind} only drives the static
     * pre-JS loading copy below. The page itself never trusts this attribute for which skill to render — the
     * script always renders from {@code study.activityType} as returned by the API, never from the URL. */
    @GetMapping("/learning/v2/reading")
    public String interactiveReading(Model model) {
        model.addAttribute("layoutMode", "app");
        model.addAttribute("comprehensionKind", "reading");
        return "learning/interactive-comprehension-study";
    }

    @GetMapping("/learning/v2/listening")
    public String interactiveListening(Model model) {
        model.addAttribute("layoutMode", "app");
        model.addAttribute("comprehensionKind", "listening");
        return "learning/interactive-comprehension-study";
    }

    private static Long userId(UserPrincipal principal) {
        return principal == null ? null : principal.userId();
    }
}
