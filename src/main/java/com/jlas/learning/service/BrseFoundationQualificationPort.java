package com.jlas.learning.service;

/** Assessment-owned verification seam for the Release-1 BrSE Japanese foundation result. */
public interface BrseFoundationQualificationPort {
    record Result(boolean qualified, String reasonCode, String assessedLevel, Integer dailyStudyMinutes) {
        public static Result rejected(String reasonCode) {
            return new Result(false, reasonCode, null, null);
        }
    }

    Result verify(Long userId, Long attemptId);
}
