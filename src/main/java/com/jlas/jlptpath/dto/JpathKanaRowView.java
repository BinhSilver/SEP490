package com.jlas.jlptpath.dto;

import java.util.List;

/**
 * [JLAS-W0108] One kana row (あ, か, …) of the kana table; {@code kind} BASIC|DAKUTEN|HANDAKUTEN|YOON,
 * {@code passed} when every kana of the row is counted.
 */
public record JpathKanaRowView(String code, String title, String kind, boolean passed, List<JpathKanaCellView> kanas) {
}
