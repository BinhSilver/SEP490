package com.jlas.jlptpath.dto;

import com.jlas.jlptpath.enums.JpathSkill;
import com.jlas.jlptpath.enums.JpathStage;
import java.util.List;

/**
 * [JLAS-W0108] The Đọc · Nghe tab of one level: today's skill day flag and reviews, this week's DAY sessions against
 * the sessions the calendar asks for (skill days × skills with content), one card per skill (type of the day #37,
 * floors #34, the open DAY session to resume), the type rows in exam order and whether section practice is open (#40).
 * {@code readiness} is null until exam readiness is built.
 */
public record JpathSkillOverview(String level, JpathStage stage, boolean skillDay, int reviewsDue,
                                 int minutesEstimate, int sessionsThisWeek, int sessionsRequiredThisWeek,
                                 SkillCard reading, SkillCard listening, List<TypeRow> types, boolean sectionOpen,
                                 JpathReadiness readiness) {

    /**
     * One skill: {@code typeCode} null when no type of the skill is available; {@code openSessionId} = today's open
     * DAY session; {@code doneToday} = today's DAY session is done.
     */
    public record SkillCard(JpathSkill skill, String typeCode, String typeNameVi, int floorPassages,
                            int floorQuestions, int dictationFloor, Long openSessionId, boolean doneToday) {
    }

    /** One question type; {@code lastPct} rounded, null when never done; {@code available} = lesson and sets visible. */
    public record TypeRow(String code, JpathSkill skill, String nameVi, String nameJa, int sessionsDone,
                          Integer lastPct, boolean available) {
    }
}
