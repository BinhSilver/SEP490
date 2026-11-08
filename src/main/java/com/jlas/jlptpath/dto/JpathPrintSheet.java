package com.jlas.jlptpath.dto;

import java.util.List;

/**
 * [JLAS-W0108] A writing sheet ready to print: {@code pages} of at most 6 rows; {@code dateVi} is the learner's day as
 * dd/MM/yyyy. {@code notice} is the line shown above the pages: the 120-item cut, the empty message, or null.
 */
public record JpathPrintSheet(String title, String dateVi, String scope, boolean truncated,
                              List<List<JpathPrintRow>> pages, String notice) {
}
