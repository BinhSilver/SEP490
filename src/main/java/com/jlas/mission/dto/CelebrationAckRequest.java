package com.jlas.mission.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Celebrations the page has finished animating. */
public record CelebrationAckRequest(@NotNull @Size(max = 50) List<Long> ids) {
}
