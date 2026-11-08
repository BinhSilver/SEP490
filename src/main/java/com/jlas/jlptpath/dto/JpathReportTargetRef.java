package com.jlas.jlptpath.dto;

import com.jlas.jlptpath.enums.JpathItemType;
import com.jlas.jlptpath.enums.JpathReportTarget;

/**
 * [JLAS-W0108] A resolved report target and the item that owns it: EXAMPLE → its owner word/kanji/pattern, QUESTION →
 * its pattern, otherwise the item itself. {@code level} is null for kana; kana are never {@code authored}.
 * {@code hidden} = the target row is already hidden by reports.
 */
public record JpathReportTargetRef(JpathReportTarget type, String code, JpathItemType itemType, String itemCode,
                                   String level, boolean authored, boolean hidden) {
}
