package com.jlas.jlptpath.dto;

import jakarta.validation.constraints.NotEmpty;
import java.util.Map;

/** [JLAS-W0108] Field edits of one target: column name → new value (empty or null = clear the column). */
public record JpathContentEditRequest(@NotEmpty Map<String, String> fields) {
}
