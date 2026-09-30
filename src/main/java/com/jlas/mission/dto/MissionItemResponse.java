package com.jlas.mission.dto;

/** One daily mission and whether its reward can be claimed. */
public record MissionItemResponse(String code, String title, String description, int progress, int target,
                                  int reward, boolean completed, boolean claimed) {
}
