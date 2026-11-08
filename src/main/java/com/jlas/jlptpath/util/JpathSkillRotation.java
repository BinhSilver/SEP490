package com.jlas.jlptpath.util;

import com.jlas.jlptpath.enums.JpathSkill;
import com.jlas.jlptpath.util.JpathSkillPolicy.Floor;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * [JLAS-W0108] Type of the day (policy #37) and set picking for skill sessions (#39 order, #34 floor and minute
 * budget) and section practice (#40, one set per type). No state, no I/O; never throws for a small pool.
 */
public final class JpathSkillRotation {

    /** Sets used within this many days are reused only when nothing older is left (#39: 8 weeks). */
    public static final int REUSE_AFTER_DAYS = 56;
    /** Extra sets stop at this multiple of the floor's question count (#34). */
    public static final int MAX_FLOOR_MULTIPLE = 2;

    private static final double EPSILON = 1e-9;

    private static final Comparator<SetInfo> BY_ORDER =
            Comparator.comparingInt(SetInfo::sortOrder).thenComparing(SetInfo::code);
    private static final Comparator<SetInfo> OLDEST_FIRST =
            Comparator.comparing(SetInfo::lastUsed).thenComparing(BY_ORDER);

    private JpathSkillRotation() {
    }

    /**
     * Learner statistics of one question type.
     *
     * @param lastPct   score of the last session's practice questions; null when never done
     * @param lastDay   study day of the last session; null when never done
     * @param available the type has at least one visible set
     */
    public record TypeStat(String code, int sortOrder, int sessionsDone, BigDecimal lastPct, LocalDate lastDay,
            boolean available) {
    }

    /**
     * One visible set of a type.
     *
     * @param lastUsed study day the learner last used it; null when never used
     */
    public record SetInfo(String code, int sortOrder, int questions, int chars, int audioSeconds,
            LocalDate lastUsed) {
    }

    /**
     * Picked set codes in session order.
     *
     * @param reused   at least one set was used within the last {@link #REUSE_AFTER_DAYS} days
     * @param floorMet the picked sets reach the floor (or, for a section, every type got a set)
     */
    public record Pick(List<String> codes, boolean reused, boolean floorMet) {
        public Pick {
            codes = List.copyOf(codes);
        }
    }

    /**
     * Policy #37 among available types: the first type by {@code sortOrder} with {@code sessionsDone = 0}; when every
     * type has been done, the lowest {@code lastPct} (null counts as 0), ties → older {@code lastDay} (null first),
     * then {@code sortOrder}. Empty when no type is available.
     */
    public static Optional<String> nextType(List<TypeStat> stats) {
        List<TypeStat> available = stats == null ? List.of()
                : stats.stream().filter(TypeStat::available).toList();
        Optional<TypeStat> unstarted = available.stream()
                .filter(t -> t.sessionsDone() <= 0)
                .min(Comparator.comparingInt(TypeStat::sortOrder).thenComparing(TypeStat::code));
        if (unstarted.isPresent()) {
            return unstarted.map(TypeStat::code);
        }
        return available.stream()
                .min(Comparator.comparing((TypeStat t) -> t.lastPct() == null ? BigDecimal.ZERO : t.lastPct())
                        .thenComparing(TypeStat::lastDay, Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparingInt(TypeStat::sortOrder)
                        .thenComparing(TypeStat::code))
                .map(TypeStat::code);
    }

    /**
     * Sets for one session of a type. Candidate order (#39): never used by {@code sortOrder}; then used on or before
     * {@code today − 56} oldest first; then the rest oldest first (picking one of these sets {@code reused}). With
     * {@code floor.needsLong()} the first long set ({@link JpathSkillPolicy#LONG_PASSAGE_CHARS}) in that order goes
     * first. Sets are taken in order until the floor's passages and questions are reached, then more are added while
     * the estimated minutes (dictation floor included) stay within {@code minuteBudget} and the questions within
     * {@link #MAX_FLOOR_MULTIPLE} × floor; the first set that does not fit stops the session. The skill is LISTENING
     * when the floor has dictation lines, else READING. A pool too small gives {@code floorMet = false}.
     */
    public static Pick pickSets(List<SetInfo> sets, Floor floor, double minuteBudget, LocalDate today) {
        List<SetInfo> ordered = candidates(sets, today);
        LocalDate reuseLimit = today.minusDays(REUSE_AFTER_DAYS);
        JpathSkill skill = floor.dictation() > 0 ? JpathSkill.LISTENING : JpathSkill.READING;

        List<SetInfo> picked = new ArrayList<>();
        if (floor.needsLong()) {
            ordered.stream().filter(s -> s.chars() >= JpathSkillPolicy.LONG_PASSAGE_CHARS).findFirst()
                    .ifPresent(picked::add);
            ordered.removeAll(picked);
        }
        int questions = picked.stream().mapToInt(SetInfo::questions).sum();
        int next = 0;
        while (next < ordered.size() && (picked.size() < floor.passages() || questions < floor.questions())) {
            SetInfo s = ordered.get(next++);
            picked.add(s);
            questions += s.questions();
        }
        boolean floorMet = picked.size() >= floor.passages() && questions >= floor.questions()
                && (!floor.needsLong()
                        || picked.stream().anyMatch(s -> s.chars() >= JpathSkillPolicy.LONG_PASSAGE_CHARS));

        if (floorMet) {
            double minutes = JpathSkillPolicy.minutes(skill, 0, 0, 0, floor.dictation());
            for (SetInfo s : picked) {
                minutes += setMinutes(skill, s);
            }
            int maxQuestions = MAX_FLOOR_MULTIPLE * floor.questions();
            while (next < ordered.size()) {
                SetInfo s = ordered.get(next);
                double withSet = minutes + setMinutes(skill, s);
                if (withSet > minuteBudget + EPSILON || questions + s.questions() > maxQuestions) {
                    break;
                }
                picked.add(s);
                minutes = withSet;
                questions += s.questions();
                next++;
            }
        }
        boolean reused = picked.stream().anyMatch(s -> isRecent(s, reuseLimit));
        return new Pick(picked.stream().map(SetInfo::code).toList(), reused, floorMet);
    }

    /**
     * Section practice (#40): the first candidate (#39 order) of every type, in the map's iteration order. A type with
     * no set is skipped and makes {@code floorMet = false}.
     */
    public static Pick pickSection(Map<String, List<SetInfo>> byType, LocalDate today) {
        LocalDate reuseLimit = today.minusDays(REUSE_AFTER_DAYS);
        List<String> codes = new ArrayList<>();
        boolean reused = false;
        boolean allTypes = byType != null && !byType.isEmpty();
        if (byType != null) {
            for (List<SetInfo> sets : byType.values()) {
                List<SetInfo> ordered = candidates(sets, today);
                if (ordered.isEmpty()) {
                    allTypes = false;
                    continue;
                }
                SetInfo first = ordered.get(0);
                codes.add(first.code());
                reused |= isRecent(first, reuseLimit);
            }
        }
        return new Pick(codes, reused, allTypes);
    }

    /** Never used (by order), then used on/before the reuse limit (oldest first), then the rest (oldest first). */
    private static List<SetInfo> candidates(List<SetInfo> sets, LocalDate today) {
        if (sets == null || sets.isEmpty()) {
            return new ArrayList<>();
        }
        LocalDate reuseLimit = today.minusDays(REUSE_AFTER_DAYS);
        List<SetInfo> ordered = new ArrayList<>(sets.size());
        sets.stream().filter(s -> s.lastUsed() == null).sorted(BY_ORDER).forEach(ordered::add);
        sets.stream().filter(s -> s.lastUsed() != null && !s.lastUsed().isAfter(reuseLimit)).sorted(OLDEST_FIRST)
                .forEach(ordered::add);
        sets.stream().filter(s -> isRecent(s, reuseLimit)).sorted(OLDEST_FIRST).forEach(ordered::add);
        return ordered;
    }

    private static boolean isRecent(SetInfo s, LocalDate reuseLimit) {
        return s.lastUsed() != null && s.lastUsed().isAfter(reuseLimit);
    }

    private static double setMinutes(JpathSkill skill, SetInfo s) {
        return JpathSkillPolicy.minutes(skill, s.chars(), s.audioSeconds(), s.questions(), 0);
    }
}
