package com.jlas.kaiwa.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FishAudioConfig {

    @Value("${fish.audio.api.key:}")
    private String apiKey;

    @Value("${fish.audio.api.url:https://api.fish.audio}")
    private String apiUrl;

    /** TTS engine model header: s1, s2-pro, s2.1-pro, s2.1-pro-free */
    @Value("${fish.audio.model:s2.1-pro-free}")
    private String model;

    /** Voice model id (reference_id) for female */
    @Value("${fish.audio.voice.female:}")
    private String voiceFemale;

    /** Voice model id (reference_id) for male */
    @Value("${fish.audio.voice.male:}")
    private String voiceMale;

    public String getApiKey() {
        return apiKey;
    }

    public String getApiUrl() {
        return apiUrl;
    }

    public String getModel() {
        return model;
    }

    public String getVoiceFemale() {
        return voiceFemale;
    }

    public String getVoiceMale() {
        return voiceMale;
    }

    public String resolveVoiceId(String gender) {
        if (gender != null && gender.equalsIgnoreCase("male")) {
            return voiceMale;
        }
        return voiceFemale;
    }

    public boolean isConfigured() {
        return apiKey != null
                && !apiKey.isBlank()
                && !apiKey.startsWith("YOUR_")
                && voiceFemale != null
                && !voiceFemale.isBlank()
                && !voiceFemale.startsWith("YOUR_");
    }
}
