package com.jlas.jlptpath.util;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * [JLAS-W0108] Level a new path opens at from the Smart Test result, before capping to released levels.
 * Owner 20/11: the path opens at {@code estimatedOverallLevel} (skill levels as ranks N5=1..N1=5, averaged and
 * rounded by the Smart Test). The ability band ("Nx Nhập môn/Căn bản/Thành thạo") is only shown and explained; a
 * "Thành thạo" band no longer lifts the path one level. Without an estimated level the band's Nx is used as is;
 * anything else (no level, below N5, abandoned) → N5.
 */
public final class JpathOpenLevelRule {

    private static final Pattern LEVEL = Pattern.compile("^(N[1-5])(?:\\s.*)?$");

    private JpathOpenLevelRule() {
    }

    public static String openLevel(String estimatedOverallLevel, String abilityBand) {
        String level = level(estimatedOverallLevel);
        if (level == null) {
            level = level(abilityBand);
        }
        return level == null ? "N5" : level;
    }

    private static String level(String text) {
        if (text == null) {
            return null;
        }
        Matcher matcher = LEVEL.matcher(text.strip().toUpperCase(Locale.ROOT));
        return matcher.matches() ? matcher.group(1) : null;
    }
}
