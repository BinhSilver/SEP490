package com.jlas.jlptpath.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** [JLAS-W0108] Japanese texts of a Đọc · Nghe page that need a reading aid (passages, questions, options, lines). */
public record JpathFuriganaRequest(@NotEmpty @Size(max = 120) List<@NotNull @Size(max = 4000) String> texts) {
}
