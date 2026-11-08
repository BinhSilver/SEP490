package com.jlas.mission.dto;

/** {@code purchase} buys a freeze with credits instead of using one the learner owns. */
public record UseFreezeRequest(boolean purchase) {
}
