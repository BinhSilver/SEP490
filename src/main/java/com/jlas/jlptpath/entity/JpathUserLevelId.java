package com.jlas.jlptpath.entity;

import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** [JLAS-W0108] Primary key of {@link JpathUserLevel}. */
@Data @NoArgsConstructor @AllArgsConstructor
public class JpathUserLevelId implements Serializable {
    private Long pathId;
    private String level;
}
