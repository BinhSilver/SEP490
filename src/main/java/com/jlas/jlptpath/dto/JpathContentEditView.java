package com.jlas.jlptpath.dto;

import java.time.LocalDateTime;

/** [JLAS-W0108] One logged field change of a target (editor 0 = system); {@code exportedAt} null = not exported yet. */
public record JpathContentEditView(Long id, Long editorUserId, String field, String oldValue, String newValue,
                                   LocalDateTime createdAt, LocalDateTime exportedAt) {
}
