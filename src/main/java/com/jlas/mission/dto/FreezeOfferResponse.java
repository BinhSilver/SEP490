package com.jlas.mission.dto;

import java.time.Instant;
import java.time.LocalDate;

/**
 * An open offer to protect a missed day.
 *
 * @param deadline  end of the learner's current day; after it the streak is lost
 * @param canUseOwned the learner holds at least one freeze
 * @param canBuy    the learner has enough credits to buy one
 */
public record FreezeOfferResponse(LocalDate missedDate, Instant deadline, int streakAtRisk,
                                  boolean canUseOwned, boolean canBuy, int price) {
}
