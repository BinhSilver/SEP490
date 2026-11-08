package com.jlas.learning.dto;

import java.util.List;

/**
 * Owner 2026-10-27 (R1): shown after "Tạo lộ trình" when the Smart Test says the learner does not hold {@code weakLevel}:
 * J-LAS recommends studying that level in full ({@code stages}), with no warm-up or mistake review. {@code skipLevel} is
 * where "Vẫn học thẳng" starts instead; {@code weakSkills} are the weakest skills (empty for an unfinished test, whose
 * blanks prove nothing); {@code estimatedDays} is null until the path preview can say it.
 */
public record LearningEntryNotice(String kind, String weakLevel, String targetLevel, List<String> stages, String skipLevel,
        List<WeakSkill> weakSkills, Integer estimatedDays, Integer minutesPerDay, boolean allowSkip) {
    public LearningEntryNotice {
        stages = List.copyOf(stages);
        weakSkills = List.copyOf(weakSkills);
    }

    public record WeakSkill(String skill, String label, int correct, int total) { }
}
