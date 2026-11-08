package com.jlas.jlptpath.dto;

import java.util.List;

/**
 * [JLAS-W0108] One grammar unit of the list: {@code total} visible patterns of the unit, {@code done} of them
 * counted towards progress; {@code patterns} are the ones matching the search, in teaching order.
 */
public record JpathGrammarUnitView(int unitNo, String title, int total, int done, List<JpathGrammarRowView> patterns) {
}
