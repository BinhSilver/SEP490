package com.jlas.jlptpath.entity;

import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** [JLAS-W0108] Primary key of {@link JpathSkillTypeStat}. */
@Data @NoArgsConstructor @AllArgsConstructor
public class JpathSkillTypeStatId implements Serializable {
    private Long userId;
    private String typeCode;
}
