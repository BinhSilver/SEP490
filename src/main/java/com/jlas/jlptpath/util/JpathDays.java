package com.jlas.jlptpath.util;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import java.time.DayOfWeek;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * [JLAS-W0108] Weekday codes (MON…SUN) stored as CSV in skill_days / rest_days, always written Monday→Sunday.
 */
public final class JpathDays {

    private static final List<String> CODES = List.of("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN");

    private JpathDays() {
    }

    /** Parses a stored CSV such as "WED,SAT"; null or blank → empty set. */
    public static EnumSet<DayOfWeek> parse(String csv) {
        if (csv == null || csv.isBlank()) {
            return EnumSet.noneOf(DayOfWeek.class);
        }
        return parseCodes(List.of(csv.split(",")));
    }

    /** Parses request codes; an unknown code → JPATH_SKILL_DAYS_INVALID. */
    public static EnumSet<DayOfWeek> parseCodes(List<String> codes) {
        EnumSet<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        if (codes == null) {
            return days;
        }
        for (String code : codes) {
            int index = code == null ? -1 : CODES.indexOf(code.trim());
            if (index < 0) {
                throw new ApiException(ErrorCode.JPATH_SKILL_DAYS_INVALID);
            }
            days.add(DayOfWeek.of(index + 1));
        }
        return days;
    }

    public static String format(Collection<DayOfWeek> days) {
        return String.join(",", codes(days));
    }

    public static List<String> codes(Collection<DayOfWeek> days) {
        if (days == null) {
            return List.of();
        }
        return days.stream().distinct().sorted()
                .map(d -> CODES.get(d.getValue() - 1))
                .collect(Collectors.toUnmodifiableList());
    }

    /** Exactly 2 skill days, at most 2 rest days, no overlap. */
    public static void validate(Set<DayOfWeek> skillDays, Set<DayOfWeek> restDays) {
        if (skillDays == null || skillDays.size() != 2) {
            throw new ApiException(ErrorCode.JPATH_SKILL_DAYS_INVALID, "Hãy chọn đúng 2 ngày kỹ năng.");
        }
        Set<DayOfWeek> rest = restDays == null ? Set.of() : restDays;
        if (rest.size() > 2) {
            throw new ApiException(ErrorCode.JPATH_SKILL_DAYS_INVALID, "Chỉ chọn tối đa 2 ngày nghỉ.");
        }
        if (rest.stream().anyMatch(skillDays::contains)) {
            throw new ApiException(ErrorCode.JPATH_SKILL_DAYS_INVALID, "Ngày nghỉ không được trùng ngày kỹ năng.");
        }
    }
}
