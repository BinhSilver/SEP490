package com.jlas.kaiwa.service.dialogue;

/**
 * Output of one DialogueEngine turn (pre-LLM).
 */
public class DialogueEngineResult {

    private DialogueEngineState state;
    /** System-prompt block injected into FreeChatPromptComposer */
    private String promptBlock = "";
    private boolean forceClarify;
    private String clarificationReply;
    private double confidence = 1.0;
    private String recommendedQuestionType = "FEELING";
    private String transitionHint = "";

    public DialogueEngineState getState() {
        return state;
    }

    public void setState(DialogueEngineState state) {
        this.state = state;
    }

    public String getPromptBlock() {
        return promptBlock;
    }

    public void setPromptBlock(String promptBlock) {
        this.promptBlock = promptBlock != null ? promptBlock : "";
    }

    public boolean isForceClarify() {
        return forceClarify;
    }

    public void setForceClarify(boolean forceClarify) {
        this.forceClarify = forceClarify;
    }

    public String getClarificationReply() {
        return clarificationReply;
    }

    public void setClarificationReply(String clarificationReply) {
        this.clarificationReply = clarificationReply;
    }

    public double getConfidence() {
        return confidence;
    }

    public void setConfidence(double confidence) {
        this.confidence = confidence;
    }

    public String getRecommendedQuestionType() {
        return recommendedQuestionType;
    }

    public void setRecommendedQuestionType(String recommendedQuestionType) {
        this.recommendedQuestionType = recommendedQuestionType;
    }

    public String getTransitionHint() {
        return transitionHint;
    }

    public void setTransitionHint(String transitionHint) {
        this.transitionHint = transitionHint != null ? transitionHint : "";
    }
}
