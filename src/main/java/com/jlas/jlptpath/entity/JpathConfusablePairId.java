package com.jlas.jlptpath.entity;

import com.jlas.jlptpath.enums.JpathItemType;
import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** [JLAS-W0108] Primary key of {@link JpathConfusablePair}. */
@Data @NoArgsConstructor @AllArgsConstructor
public class JpathConfusablePairId implements Serializable {
    private JpathItemType itemType;
    private String codeA;
    private String codeB;
}
