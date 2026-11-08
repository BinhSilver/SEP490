package com.jlas.jlptpath.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

/** [JLAS-W0108] Study events of one session, in the order the learner made them (offline queue replays included). */
public record JpathStudyEventBatch(@NotEmpty @Size(max = 100) List<@Valid JpathStudyEventInput> events) {
}
