package com.jlas.jlptpath.dto;

import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * [JLAS-W0108] Entry answers from the survey: studied before, kana choice (REMEMBER|REVIEW, ignored when never
 * studied), exactly 2 skill days and up to 2 rest days as MON…SUN codes.
 */
public record JpathEntryPreferenceRequest(@NotNull Boolean studiedBefore, String kanaChoice,
                                          @NotNull List<String> skillDays, List<String> restDays) {
}
