package com.jlas.learning.dto;

/** [JLAS-W0108] A finished activity the learner can reopen from the summary in REVIEW or PRACTICE mode. */
public record InteractiveStudyLink(Long activityId, String type, String state) { }
