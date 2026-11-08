package com.jlas.jlptpath.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.jlas.jlptpath.enums.JpathSkill;
import java.util.List;

/**
 * [JLAS-W0108] Strategy lesson of one question type with its worked example and the comprehension check (#36).
 * Option ids are o1..oN in stored order. Before {@code passed} neither the example's answer nor the check's answer and
 * explanation are in the JSON (the keys are absent, not null).
 */
public record JpathSkillLessonView(String typeCode, JpathSkill skill, String titleVi, String goalVi,
                                   List<String> steps, List<String> traps, List<String> tips, Example example,
                                   CheckQ check, boolean passed) {

    /**
     * Worked example: a passage (reading) or a script and its audio (listening), the question and its options, the
     * walkthrough and translation. {@code answerOption} only once passed.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Example(String passageJa, String scriptJa, String imageDescriptionVi, String questionJa,
                          List<JpathOptionView> options, List<String> walkthroughVi, String translationVi,
                          String audioUrl, String answerOption) {
    }

    /** The comprehension question; {@code answerOption} and {@code explanationVi} only once passed. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CheckQ(String questionVi, List<JpathOptionView> options, String answerOption,
                         String explanationVi) {
    }
}
