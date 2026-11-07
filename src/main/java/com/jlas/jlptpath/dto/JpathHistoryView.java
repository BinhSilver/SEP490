package com.jlas.jlptpath.dto;

import java.time.LocalDate;
import java.util.List;

/** [JLAS-W0108] History of {@code [from, to]}: the days with activity or a plan, newest first; {@code today} is the
 * learner's study day by the app clock (E2E-002), the end of the 14/30-day ranges the page asks for. */
public record JpathHistoryView(LocalDate from, LocalDate to, List<JpathHistoryDayView> days, LocalDate today) {
}
