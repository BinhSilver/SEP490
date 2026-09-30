package com.jlas.mission.dto;

import java.time.LocalDate;

/** One day of the week strip: COMPLETED, FROZEN, MISSED, TODAY or FUTURE. */
public record StreakDayResponse(LocalDate date, String state) {
}
