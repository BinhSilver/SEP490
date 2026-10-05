package com.jlas.kaiwa.model;

import java.util.List;

/** Authored stage, also snapshotted into its attempt so seed changes do not change an active route. */
public record ScenarioStage(String code, String title, List<String> prerequisites, List<String> stateCodes,
                            List<String> requiredGoalCodes, int tier, int number, int row,
                            List<String> speakerCodes, List<ScenarioSpeaker> speakers,
                            String imageUrl, List<String> imageUrls) {}
