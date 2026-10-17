package com.jlas.kaiwa.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

/** A scenario character; voice IDs are content, not credentials. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScenarioSpeaker(String code, String name, String voiceId, String description,
                              String speechStyle, Map<String, String> voiceVariants) {}
