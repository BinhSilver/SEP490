package com.jlas.jlptpath.dto;

/**
 * [JLAS-W0108] What "Báo sai" on a locked question points at: the reported target (QUESTION for a grammar bank
 * question, otherwise the item) and the item it belongs to. Only sent after the question is locked.
 */
public record JpathReportTargetView(String targetType, String targetCode, String itemType, String itemCode) {
}
