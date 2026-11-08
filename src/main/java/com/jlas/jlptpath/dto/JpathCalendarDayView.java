package com.jlas.jlptpath.dto;

import com.jlas.jlptpath.enums.JpathDayKind;
import java.time.LocalDate;

/** [JLAS-W0108] One day of the week calendar; {@code today} follows the learner's own zone. */
public record JpathCalendarDayView(LocalDate day, JpathDayKind kind, boolean done, boolean today) {
}
