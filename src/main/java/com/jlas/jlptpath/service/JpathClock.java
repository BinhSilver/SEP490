package com.jlas.jlptpath.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * [JLAS-W0108] Time for the JLPT path. A learner's study day starts at 0:00 in the learner's own zone; never call
 * {@code LocalDate.now()} in this module.
 */
public interface JpathClock {

    /** The zone that defines the learner's day. */
    ZoneId zone(Long userId);

    /** The learner's study day. */
    LocalDate today(Long userId);

    /** Server time (Asia/Ho_Chi_Minh, like Hibernate) for ordinary timestamp columns. */
    LocalDateTime now();

    /** The learner's local wall-clock time, for the columns that store learner time. */
    LocalDateTime localNow(Long userId);

    ZonedDateTime zonedNow(Long userId);
}
