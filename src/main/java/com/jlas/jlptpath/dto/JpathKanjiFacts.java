package com.jlas.jlptpath.dto;

import java.util.List;

/** [JLAS-W0108] Kanji part of a card or item; {@code hanViet} is upper case. */
public record JpathKanjiFacts(String hanViet, List<String> hanVietAlt, List<String> readingsOn, List<String> readingsKun,
                              List<String> levelReadings, Integer strokeCount, Integer radicalNumber, String radicalName,
                              String svgUrl) {
}
