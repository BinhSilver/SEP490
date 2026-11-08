package com.jlas.jlptpath.dto;

/**
 * [JLAS-W0108] Filter of the editor's "Báo sai" queue. {@code status} OPEN (default), RESOLVED or REJECTED;
 * {@code level} N5..N1 or null for every level; {@code page} 0-based; {@code size} 20 by default, at most 50.
 */
public record JpathReportQueueFilter(String status, String level, int page, int size) {
}
