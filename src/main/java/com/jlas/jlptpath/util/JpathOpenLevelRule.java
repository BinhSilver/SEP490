package com.jlas.jlptpath.util;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * [JLAS-W0108] Level a new path opens at from the Smart Test ability band (policy #28, before capping to released
 * levels): "Nx Thành thạo" → the level above Nx (N1 stays N1); Nx with Căn bản, Nhập môn, (tạm tính) or nothing → Nx;
 * anything else (no level, below N5, abandoned) → N5.
 */
public final class JpathOpenLevelRule {

    private static final Pattern BAND = Pattern.compile("^(N[1-5])\s*(.*)$");
    private static final String MASTERED = "Thành thạo";
    private static final List<String> LEVELS = List.of("N5", "N4", "N3", "N2", "N1");

    private JpathOpenLevelRule() {
    }

    public static String openLevel(String abilityBand) {
        if (abilityBand == null) {
            return "N5";
        }
        Matcher matcher = BAND.matcher(abilityBand.strip());
        if (!matcher.matches()) {
            return "N5";
        }
        String level = matcher.group(1);
        if (!matcher.group(2).strip().equals(MASTERED)) {
            return level;
        }
        int index = LEVELS.indexOf(level);
        return LEVELS.get(Math.min(index + 1, LEVELS.size() - 1));
    }
}
