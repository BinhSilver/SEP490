package com.jlas.jlptpath.dto;

import java.util.List;

/**
 * [JLAS-W0108] Skill part of a SKILL_PRACTICE / SKILL_DICTATION check (Plan 3 Task 13): the sets in order with the ids
 * of their questions. Before every question of a set is locked only what the learner needs to answer is sent (the
 * Reading passage, the Listening audio slot and picture description); once the set is locked, {@code revealed} is
 * true and the translation, the script and the new words follow. {@code skillSessionId} is the session to go back to;
 * {@code dictation} is empty except for a SKILL_DICTATION check. A timed section (Plan 3 Task 15/22) adds the server's
 * {@code deadlineAt} (Reading) and {@code serverNow} (both server-local ISO times, so the page counts down without
 * trusting the device clock) and {@code perSetAnswerSeconds} (Listening).
 */
public record JpathSkillCheckView(Long skillSessionId, List<SetView> sets, List<DictationLine> dictation,
                                  String deadlineAt, String serverNow, Integer perSetAnswerSeconds) {

    public JpathSkillCheckView(Long skillSessionId, List<SetView> sets) {
        this(skillSessionId, sets, List.of(), null, null, null);
    }

    public JpathSkillCheckView(Long skillSessionId, List<SetView> sets, List<DictationLine> dictation) {
        this(skillSessionId, sets, dictation, null, null, null);
    }

    /**
     * One dictation line (Plan 3 Task 14): {@code mask} has a {@code ＿＿} per blank and never the blank's text. Once
     * locked: the sentence, the expected text of each blank, what was typed and each verdict (EXACT, MINOR, WRONG).
     */
    public record DictationLine(String questionId, Integer audioSlot, String mask, int blanks, boolean locked,
                                String textJa, List<String> expected, List<String> typed, List<String> verdicts) {
    }

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
