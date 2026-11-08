package com.jlas.jlptpath.dto;

import com.jlas.jlptpath.enums.JpathTab;
import java.util.List;
import java.util.Map;

/**
 * [JLAS-W0108] Sliders of the open level. {@code nextChangeCounted} = the next change uses one of the 2 adjustments
 * (the first drag after creating the path is free); {@code appliesFromTomorrow} = tabs already started today, where a
 * change only applies from tomorrow (policy #29).
 */
public record JpathSliderView(int vocab, int kanji, int grammar, Map<JpathTab, JpathSliderBoundView> bounds,
        int estimatedMinutes, int dailyMinutes, boolean overBudget, int adjustmentsLeft, boolean nextChangeCounted,
        List<JpathTab> appliesFromTomorrow) {
}
