package com.jlas.learning.dto;

/** A mapped Smart Test item kept visible for read-only review when a skill has fewer than five targets. */
public record SmartTestReviewTarget(String skill, String target) { }
