package com.jlas.kaiwa.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * User-selected free-chat behavior options.
 * Each field maps to a modular prompt file under {@code classpath:prompts/}.
 */
public class FreeChatSettings {

    /** When true: load conversation continuation / balance / topic modules. */
    private boolean conversationActive = true;

    private String personality = "friendly";
    private String level = "n3";
    private String style = "casual";
    private String emotion = "friendly";
    private String initiative = "medium";
    private String responseLength = "medium";
    private String conversationMode = "free-talk";
    private List<String> favoriteTopics = new ArrayList<>();

    public static FreeChatSettings defaults() {
        return new FreeChatSettings();
    }

    public boolean isConversationActive() {
        return conversationActive;
    }

    public void setConversationActive(boolean conversationActive) {
        this.conversationActive = conversationActive;
    }

    public String getPersonality() {
        return personality;
    }

    public void setPersonality(String personality) {
        this.personality = personality;
    }

    public String getLevel() {
        return level;
    }

    public void setLevel(String level) {
        this.level = level;
    }

    public String getStyle() {
        return style;
    }

    public void setStyle(String style) {
        this.style = style;
    }

    public String getEmotion() {
        return emotion;
    }

    public void setEmotion(String emotion) {
        this.emotion = emotion;
    }

    public String getInitiative() {
        return initiative;
    }

    public void setInitiative(String initiative) {
        this.initiative = initiative;
    }

    public String getResponseLength() {
        return responseLength;
    }

    public void setResponseLength(String responseLength) {
        this.responseLength = responseLength;
    }

    public String getConversationMode() {
        return conversationMode;
    }

    public void setConversationMode(String conversationMode) {
        this.conversationMode = conversationMode;
    }

    public List<String> getFavoriteTopics() {
        return favoriteTopics;
    }

    public void setFavoriteTopics(List<String> favoriteTopics) {
        this.favoriteTopics = favoriteTopics != null ? favoriteTopics : new ArrayList<>();
    }
}
