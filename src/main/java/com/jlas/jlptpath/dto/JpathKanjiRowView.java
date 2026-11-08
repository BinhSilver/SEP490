package com.jlas.jlptpath.dto;

import java.util.List;

/** [JLAS-W0108] One row of the kanji list; state NEW|LEARNING|NOTEBOOK|KNOWN, Hán Việt in upper case. */
public record JpathKanjiRowView(String code, String character, String hanViet, String meaning, List<String> readingsOn,
                                List<String> readingsKun, List<String> levelReadings, Integer strokeCount,
                                Integer radicalNumber, String radicalName, Integer frequency, String state,
                                boolean learnable, String svgUrl) {
}
