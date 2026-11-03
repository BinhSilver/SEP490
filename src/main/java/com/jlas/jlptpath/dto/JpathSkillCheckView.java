package com.jlas.jlptpath.dto;

import java.util.List;

/**
 * [JLAS-W0108] Skill part of a SKILL_PRACTICE / SKILL_DICTATION check (Plan 3 Task 13): the sets in order with the ids
 * of their questions. Before every question of a set is locked only what the learner needs to answer is sent (the
 * Reading passage, the Listening audio slot and picture description); once the set is locked, {@code revealed} is
 * true and the translation, the script and the new words follow. {@code skillSessionId} is the session to go back to.
 */
public record JpathSkillCheckView(Long skillSessionId, List<SetView> sets) {

    public record SetView(String code, int index, String skill, String passageJa, Integer audioSlot,
                          String imageDescriptionVi, List<String> questionIds, boolean revealed, String passageVi,
                          List<ScriptLine> script, String scriptVi, List<NewWord> newWords) {
    }

    public record ScriptLine(String speaker, String ja) {
    }

    /** {@code vocabCode}: the visible path word it matches (notebook button), null → "Tra từ" (Look up). */
    public record NewWord(String expression, String reading, String meaningVi, String vocabCode) {
    }
}
