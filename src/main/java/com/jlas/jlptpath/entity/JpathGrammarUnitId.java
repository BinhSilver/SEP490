package com.jlas.jlptpath.entity;

import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** [JLAS-W0108] Primary key of {@link JpathGrammarUnit}. */
@Data @NoArgsConstructor @AllArgsConstructor
public class JpathGrammarUnitId implements Serializable {
    private String level;
    private Integer unitNo;
}
