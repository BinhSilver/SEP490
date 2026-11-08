package com.jlas.jlptpath.dto;

import java.util.List;

/**
 * [JLAS-W0108] Feature switch and level release state of the JLPT path, read by the survey and Smart Test pages.
 * When the path is switched off: {@code enabled=false}, empty lists and no highest level.
 */
public record JpathConfigView(boolean enabled, List<String> releasedLevels, String highestReleasedLevel,
                              List<String> comingSoonLevels) {

    public static JpathConfigView disabled() {
        return new JpathConfigView(false, List.of(), null, List.of());
    }
}
