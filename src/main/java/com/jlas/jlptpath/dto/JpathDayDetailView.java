package com.jlas.jlptpath.dto;

import com.jlas.jlptpath.enums.JpathDayKind;
import java.time.LocalDate;
import java.util.List;

/**
 * [JLAS-W0108] Detail of one history day: the items touched (first touched first), the submitted checks and the
 * items answered wrong or rated "Chưa nhớ" (each once, in the order they first went wrong).
 */
public record JpathDayDetailView(LocalDate day, JpathDayKind kind, boolean done, int cpAwarded,
                                 List<JpathDayItemView> items, List<JpathCheckSummaryView> checks,
                                 List<JpathDayItemView> wrongItems) {
}
