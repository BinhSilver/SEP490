package com.jlas.jlptpath.entity;

import com.jlas.jlptpath.enums.JpathTab;
import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** [JLAS-W0108] Primary key of {@link JpathDailyTab}. */
@Data @NoArgsConstructor @AllArgsConstructor
public class JpathDailyTabId implements Serializable {
    private Long planId;
    private JpathTab tab;
}
