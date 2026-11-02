package com.jlas.jlptpath.dto;

import jakarta.validation.constraints.Size;

/** [JLAS-W0108] Submit of a check; {@code submitKey} is an optional client key stored with the submit. */
public record JpathCheckSubmitRequest(@Size(max = 40) String submitKey) {
}
