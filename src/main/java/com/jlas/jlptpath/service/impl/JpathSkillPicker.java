package com.jlas.jlptpath.service.impl;

import com.jlas.jlptpath.entity.JpathSkillSetUse;
import com.jlas.jlptpath.entity.JpathSkillType;
import com.jlas.jlptpath.entity.JpathSkillTypeStat;
import com.jlas.jlptpath.enums.JpathSkill;
import com.jlas.jlptpath.repository.JpathSkillSetUseRepository;
import com.jlas.jlptpath.repository.JpathSkillTypeStatRepository;
import com.jlas.jlptpath.service.impl.JpathSkillContentReader.VisibleSet;
import com.jlas.jlptpath.util.JpathSkillPolicy;
import com.jlas.jlptpath.util.JpathSkillRotation;
import com.jlas.jlptpath.util.JpathSkillRotation.Pick;
import com.jlas.jlptpath.util.JpathSkillRotation.SetInfo;
import com.jlas.jlptpath.util.JpathSkillRotation.TypeStat;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * [JLAS-W0108] Feeds the learner's type statistics and set use into {@link JpathSkillRotation}: the type of the day
 * (#37) and the sets of a session (#34 floor, budget = daily minutes / 2, #39 order). Reads only.
 */
@Component
public class JpathSkillPicker {

    /** Visible skill content of one level: types in exam order, visible sets by type, types with a visible lesson. */
    public record LevelContent(List<JpathSkillType> types, Map<String, List<VisibleSet>> sets, Set<String> lessons) {

        public boolean available(JpathSkillType type) {
            return JpathSkillContentReader.available(type, lessons, sets);
        }

        public List<JpathSkillType> types(JpathSkill skill) {
            return types.stream().filter(t -> t.getSkill() == skill).toList();
        }

        public boolean hasAvailable(JpathSkill skill) {
            return types(skill).stream().anyMatch(this::available);
        }
    }

    private final JpathSkillContentReader reader;
    private final JpathSkillTypeStatRepository stats;
    private final JpathSkillSetUseRepository uses;

    public JpathSkillPicker(JpathSkillContentReader reader, JpathSkillTypeStatRepository stats,
            JpathSkillSetUseRepository uses) {
        this.reader = reader;
        this.stats = stats;
        this.uses = uses;
    }

    /** Visible skill content of {@code level} (empty when the level is not released). */
    public LevelContent content(String level) {
        List<JpathSkillType> types = reader.types(level);
        return new LevelContent(types, reader.visibleSets(types), reader.typesWithLesson(types));
    }

    /** The learner's statistics of these types by type code. */
    public Map<String, JpathSkillTypeStat> stats(Long userId, Collection<JpathSkillType> types) {
        List<String> codes = types.stream().map(JpathSkillType::getCode).toList();
        if (codes.isEmpty()) {
            return Map.of();
        }
        return stats.findByUserIdAndTypeCodeIn(userId, codes).stream()
                .collect(Collectors.toMap(JpathSkillTypeStat::getTypeCode, Function.identity(), (a, b) -> a));
    }

    /** Policy #37 for one skill of the level; empty when no type of the skill is available. */
    public Optional<String> nextType(Long userId, LevelContent content, JpathSkill skill) {
        List<JpathSkillType> types = content.types(skill);
        Map<String, JpathSkillTypeStat> byCode = stats(userId, types);
        List<TypeStat> in = new ArrayList<>(types.size());
        for (JpathSkillType t : types) {
            JpathSkillTypeStat s = byCode.get(t.getCode());
            in.add(new TypeStat(t.getCode(), t.getSortOrder(), s == null ? 0 : s.getSessionsDone(),
                    s == null ? null : s.getLastPct(), s == null ? null : s.getLastSessionDay(),
                    content.available(t)));
        }
        return JpathSkillRotation.nextType(in);
    }

    /** Sets of one session of {@code type} (#34, #39); budget for the skill = {@code dailyMinutes / 2}. */
    public Pick pick(Long userId, JpathSkillType type, List<VisibleSet> sets, int dailyMinutes, LocalDate today) {
        JpathSkillPolicy.Floor floor = JpathSkillPolicy.floor(type.getLevel(), type.getSkill());
        return JpathSkillRotation.pickSets(setInfos(userId, sets), floor, dailyMinutes / 2.0, today);
    }

    /** Estimated minutes of the picked sets plus the dictation floor of the skill (#34). */
    public static double minutes(JpathSkillType type, List<VisibleSet> sets, Collection<String> picked) {
        JpathSkillPolicy.Floor floor = JpathSkillPolicy.floor(type.getLevel(), type.getSkill());
        double minutes = JpathSkillPolicy.minutes(type.getSkill(), 0, 0, 0, floor.dictation());
        for (VisibleSet v : sets) {
            if (picked.contains(v.set().getCode())) {
                minutes += JpathSkillPolicy.minutes(type.getSkill(), nz(v.set().getCharCount()),
                        nz(v.set().getAudioSeconds()), v.questions(), 0);
            }
        }
        return minutes;
    }

    private List<SetInfo> setInfos(Long userId, List<VisibleSet> sets) {
        if (sets.isEmpty()) {
            return List.of();
        }
        Map<String, LocalDate> lastUsed = uses.findByUserIdAndSetCodeIn(userId,
                        sets.stream().map(v -> v.set().getCode()).toList()).stream()
                .collect(Collectors.toMap(JpathSkillSetUse::getSetCode, JpathSkillSetUse::getLastUsedDay, (a, b) -> a));
        return sets.stream().map(v -> new SetInfo(v.set().getCode(), v.set().getSortOrder(), v.questions(),
                nz(v.set().getCharCount()), nz(v.set().getAudioSeconds()), lastUsed.get(v.set().getCode()))).toList();
    }

    private static int nz(Integer n) {
        return n == null ? 0 : n;
    }
}
