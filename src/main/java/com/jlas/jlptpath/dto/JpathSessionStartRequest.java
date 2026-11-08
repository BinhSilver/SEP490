package com.jlas.jlptpath.dto;

import com.jlas.jlptpath.enums.JpathSessionMode;
import com.jlas.jlptpath.enums.JpathSessionTab;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * [JLAS-W0108] Start (or resume) a study session. {@code level} only for LOWER_LEVEL and BROWSE; {@code topicKey} /
 * {@code unitNo} / {@code startItemCode} narrow a BROWSE session ({@code startItemCode} also puts a hero card first in
 * a TODAY session); {@code count} = number of new items (EXTRA 5, LOWER_LEVEL and BROWSE 10 when null; kana rows for
 * the KANA tab, 1 when null), 1..200.
 */
public record JpathSessionStartRequest(@NotNull JpathSessionTab tab, @NotNull JpathSessionMode mode, String level,
                                       String topicKey, Integer unitNo, String startItemCode, @Min(1) @Max(200) Integer count) {
}
