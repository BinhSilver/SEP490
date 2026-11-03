package com.jlas.jlptpath.dto;

import com.jlas.jlptpath.enums.JpathSkill;
import com.jlas.jlptpath.enums.JpathSkillSessionKind;
import com.jlas.jlptpath.enums.JpathSkillStep;

/**
 * [JLAS-W0108] A skill session. Questions and dictation lines are served through the check routes with
 * {@code practiceToken} / {@code dictationToken} (null until the check is issued); {@code questionCount} and
 * {@code dictationCount} are the issued check's size when there is one, else the visible questions of the sets and
 * the dictation floor (#34). No content and no answer is part of this view.
 */
public record JpathSkillSessionView(Long id, JpathSkill skill, JpathSkillSessionKind kind, String typeCode,
                                    JpathSkillStep step, int setCount, int questionCount, int dictationCount,
                                    boolean reused, String practiceToken, String dictationToken, boolean done,
                                    int cpAwarded) {
}
