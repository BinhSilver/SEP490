package com.jlas.jlptpath.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * [JLAS-W0108] A learner's content error report. {@code targetType} is VOCAB, KANJI, GRAMMAR, KANA, EXAMPLE or
 * QUESTION; {@code itemType}/{@code itemCode} are only hints from the page — the owning item is always derived on the
 * server. {@code errorType} is MEANING, EXAMPLE, AUDIO, KANJI or OTHER; {@code pageContext} names the screen.
 */
public record JpathReportRequest(@NotBlank String targetType, @NotBlank @Size(max = 24) String targetCode,
                                 String itemType, String itemCode, @NotBlank String errorType,
                                 @Size(max = 500) String note, @Pattern(regexp = "^[A-Z_]{1,40}$") String pageContext) {
}
