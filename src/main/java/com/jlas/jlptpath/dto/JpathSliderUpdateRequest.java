package com.jlas.jlptpath.dto;

import jakarta.validation.constraints.NotNull;

/** [JLAS-W0108] New items per day the learner sets on the open level's sliders (policy #29). */
public record JpathSliderUpdateRequest(@NotNull Integer vocab, @NotNull Integer kanji, @NotNull Integer grammar) {
}
