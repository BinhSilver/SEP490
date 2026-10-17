package com.jlas.learning.dto;

/** Server-owned recommendation shown only after a learning path reaches a terminal state. */
public record NextTargetRecommendation(
        boolean available,
        String recommendedTarget,
        String reasonCode,
        String actionUrl) {

    public static NextTargetRecommendation next(String target) {
        return new NextTargetRecommendation(true, target, "NEXT_JLPT_LEVEL", "/survey");
    }

    public static NextTargetRecommendation unavailable(String reasonCode) {
        return new NextTargetRecommendation(false, null, reasonCode, null);
    }
}
