package com.jlas.kaiwa.service.understanding;

import java.util.ArrayList;
import java.util.List;

/**
 * Result of the pre-LLM Conversation Understanding layer.
 */
public class ConversationUnderstanding {

    public enum Intent {
        /** Normal free chat turn */
        CHAT,
        /** User asks meaning of a word (often from recent AI speech) */
        VOCAB_HELP,
        /** Multiple plausible meanings — must clarify, do not assume */
        AMBIGUOUS,
        /** Very short ack */
        SHORT_ACK
    }

    private Intent intent = Intent.CHAT;
    /** 0.0 – 1.0 confidence in the primary interpretation */
    private double confidence = 1.0;
    private String vocabTarget;
    private boolean vocabFromRecentAi;
    private String vocabContextSnippet;
    private List<String> ambiguityOptions = new ArrayList<>();
    private String lastAssistantMessage = "";
    private List<String> recentAiTerms = new ArrayList<>();
    /** Extra system block injected into Prompt Builder */
    private String promptBlock = "";
    /** If true, pipeline may short-circuit with clarificationReply without free exploration */
    private boolean forceClarify;
    private String clarificationReply;

    public Intent getIntent() {
        return intent;
    }

    public void setIntent(Intent intent) {
        this.intent = intent;
    }

    public double getConfidence() {
        return confidence;
    }

    public void setConfidence(double confidence) {
        this.confidence = confidence;
    }

    public String getVocabTarget() {
        return vocabTarget;
    }

    public void setVocabTarget(String vocabTarget) {
        this.vocabTarget = vocabTarget;
    }

    public boolean isVocabFromRecentAi() {
        return vocabFromRecentAi;
    }

    public void setVocabFromRecentAi(boolean vocabFromRecentAi) {
        this.vocabFromRecentAi = vocabFromRecentAi;
    }

    public String getVocabContextSnippet() {
        return vocabContextSnippet;
    }

    public void setVocabContextSnippet(String vocabContextSnippet) {
        this.vocabContextSnippet = vocabContextSnippet;
    }

    public List<String> getAmbiguityOptions() {
        return ambiguityOptions;
    }

    public void setAmbiguityOptions(List<String> ambiguityOptions) {
        this.ambiguityOptions = ambiguityOptions != null ? ambiguityOptions : new ArrayList<>();
    }

    public String getLastAssistantMessage() {
        return lastAssistantMessage;
    }

    public void setLastAssistantMessage(String lastAssistantMessage) {
        this.lastAssistantMessage = lastAssistantMessage != null ? lastAssistantMessage : "";
    }

    public List<String> getRecentAiTerms() {
        return recentAiTerms;
    }

    public void setRecentAiTerms(List<String> recentAiTerms) {
        this.recentAiTerms = recentAiTerms != null ? recentAiTerms : new ArrayList<>();
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

    public boolean isVocabHelp() {
        return intent == Intent.VOCAB_HELP;
    }

    public boolean isAmbiguous() {
        return intent == Intent.AMBIGUOUS;
    }
}
