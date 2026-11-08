package com.jlas.learning.dto;

/** [JLAS-W0108] Task 10 - {@code direction} is LIGHTER, SAME or FASTER; {@code newMinutesPerDay} and
 * {@code previewToken} come from the preview the learner just confirmed. The token stops a stale screen from
 * silently re-planning different progress, and the target lets a repeated click be recognised as already applied. */
public record LearningPaceRequest(String direction, Integer newMinutesPerDay, String previewToken) { }
