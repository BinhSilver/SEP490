package com.jlas.jlptpath.entity;

import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** [JLAS-W0108] Primary key of {@link JpathKanjiCompound}. */
@Data @NoArgsConstructor @AllArgsConstructor
public class JpathKanjiCompoundId implements Serializable {
    private String kanjiCode;
    private String vocabCode;
}
