package com.jlas.jlptpath.dto;

import com.jlas.jlptpath.enums.JpathItemType;

/** [JLAS-W0108] An item answered wrong or rated "Chưa nhớ" today, for the day summary. */
public record JpathWrongItemView(JpathItemType itemType, String code, String expression, String reading,
                                 String meaning) {
}
