package com.jlas.kaiwa.dto;

import java.util.ArrayList;
import java.util.List;

public class ChatTurnResponse {
    private Long assistantMessageId;
    private com.jlas.kaiwa.model.ScenarioSpeaker assistantSpeaker;
    private com.jlas.kaiwa.model.ScenarioSpeaker currentSpeaker;
    private AssistantOpening speakerOpening;
    public record AssistantOpening(Long messageId, String text, com.jlas.kaiwa.model.ScenarioSpeaker speaker) {}
    public Long getAssistantMessageId() { return assistantMessageId; }
    public void setAssistantMessageId(Long value) { assistantMessageId = value; }
    public com.jlas.kaiwa.model.ScenarioSpeaker getAssistantSpeaker() { return assistantSpeaker; }
    public void setAssistantSpeaker(com.jlas.kaiwa.model.ScenarioSpeaker value) { assistantSpeaker = value; }
    public com.jlas.kaiwa.model.ScenarioSpeaker getCurrentSpeaker() { return currentSpeaker; }
    public void setCurrentSpeaker(com.jlas.kaiwa.model.ScenarioSpeaker value) { currentSpeaker = value; }
    public AssistantOpening getSpeakerOpening() { return speakerOpening; }
    public void setSpeakerOpening(AssistantOpening value) { speakerOpening = value; }
    private Long sessionId;
    private String assistantReply;
    private String currentState;
    private int progressPercent;
    private List<String> completedObjectives = new ArrayList<>();
    private List<String> remainingObjectives = new ArrayList<>();
    private List<String> allObjectives = new ArrayList<>();
    private FeedbackCardDto feedback;
    private boolean missionComplete;
    private boolean conversationEnded;
    private String error;
    private List<FuriganaTokenDto> assistantTokens = new ArrayList<>();
    private List<String> suggestedReplies = new ArrayList<>();
    private String sceneTransition;
    private String sceneImageUrl;
    public String getSceneImageUrl() { return sceneImageUrl; }
    public void setSceneImageUrl(String value) { sceneImageUrl = value; }
    /** Cách tính phí lượt này: FREE / QUOTA / CREDIT (JLAS-W0199). */
    private String chargeType;
    /** Số CP đã trừ cho lượt này (0 nếu dùng hạn mức). */
    private int creditCharged;
    /** Lượt lỗi nên phút/CP đã được hoàn lại. */
    private boolean chargeRefunded;

    public Long getSessionId() { return sessionId; }
    public void setSessionId(Long sessionId) { this.sessionId = sessionId; }
    public String getAssistantReply() { return assistantReply; }
    public void setAssistantReply(String assistantReply) { this.assistantReply = assistantReply; }
    public String getCurrentState() { return currentState; }
    public void setCurrentState(String currentState) { this.currentState = currentState; }
    public int getProgressPercent() { return progressPercent; }
    public void setProgressPercent(int progressPercent) { this.progressPercent = progressPercent; }
    public List<String> getCompletedObjectives() { return completedObjectives; }
    public void setCompletedObjectives(List<String> completedObjectives) { this.completedObjectives = completedObjectives; }
    public List<String> getRemainingObjectives() { return remainingObjectives; }
    public void setRemainingObjectives(List<String> remainingObjectives) { this.remainingObjectives = remainingObjectives; }
    public FeedbackCardDto getFeedback() { return feedback; }
    public void setFeedback(FeedbackCardDto feedback) { this.feedback = feedback; }
    public boolean isMissionComplete() { return missionComplete; }
    public void setMissionComplete(boolean missionComplete) { this.missionComplete = missionComplete; }
    public boolean isConversationEnded() { return conversationEnded; }
    public void setConversationEnded(boolean conversationEnded) { this.conversationEnded = conversationEnded; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public List<String> getAllObjectives() { return allObjectives; }
    public void setAllObjectives(List<String> allObjectives) { this.allObjectives = allObjectives; }
    public List<FuriganaTokenDto> getAssistantTokens() { return assistantTokens; }
    public void setAssistantTokens(List<FuriganaTokenDto> assistantTokens) { this.assistantTokens = assistantTokens; }
    public List<String> getSuggestedReplies() { return suggestedReplies; }
    public void setSuggestedReplies(List<String> suggestedReplies) { this.suggestedReplies = suggestedReplies; }
    public String getSceneTransition() { return sceneTransition; }
    public void setSceneTransition(String sceneTransition) { this.sceneTransition = sceneTransition; }
    public String getChargeType() { return chargeType; }
    public void setChargeType(String chargeType) { this.chargeType = chargeType; }
    public int getCreditCharged() { return creditCharged; }
    public void setCreditCharged(int creditCharged) { this.creditCharged = creditCharged; }
    public boolean isChargeRefunded() { return chargeRefunded; }
    public void setChargeRefunded(boolean chargeRefunded) { this.chargeRefunded = chargeRefunded; }
}
