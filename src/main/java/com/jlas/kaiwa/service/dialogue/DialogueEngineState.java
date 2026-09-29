package com.jlas.kaiwa.service.dialogue;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Backend-owned free-chat dialogue state. Serialized to session.engineStateJson.
 * LLM must not "remember" this — only the current snapshot is injected each turn.
 */
public class DialogueEngineState {

    private DialoguePhase currentPhase = DialoguePhase.GREETING;
    private DialoguePhase previousPhase;
    private int stateStartTurn;
    private int turnsInCurrentPhase;
    private int turnIndex;

    /** Entity stack: root  …  leaf (active entity is last). */
    private List<DialogueEntity> entityStack = new ArrayList<>();

    private String activeTopic = "";
    private int topicDepth;
    private int exploredNodes;
    private boolean topicExhausted;

    private int shortReplyCount;
    private int lastRecoveryTurn = -1;
    private int recoveryStep;

    private List<String> lastQuestionTypes = new ArrayList<>();

    private List<String> favoriteTopics = new ArrayList<>();
    private Set<String> mentionedEntities = new LinkedHashSet<>();

    private String lastResolvedPronoun;
    private String lastResolvedReferent;

    public DialoguePhase getCurrentPhase() {
        return currentPhase;
    }

    public void setCurrentPhase(DialoguePhase currentPhase) {
        this.currentPhase = currentPhase != null ? currentPhase : DialoguePhase.GREETING;
    }

    public DialoguePhase getPreviousPhase() {
        return previousPhase;
    }

    public void setPreviousPhase(DialoguePhase previousPhase) {
        this.previousPhase = previousPhase;
    }

    public int getStateStartTurn() {
        return stateStartTurn;
    }

    public void setStateStartTurn(int stateStartTurn) {
        this.stateStartTurn = stateStartTurn;
    }

    public int getTurnsInCurrentPhase() {
        return turnsInCurrentPhase;
    }

    public void setTurnsInCurrentPhase(int turnsInCurrentPhase) {
        this.turnsInCurrentPhase = turnsInCurrentPhase;
    }

    public int getTurnIndex() {
        return turnIndex;
    }

    public void setTurnIndex(int turnIndex) {
        this.turnIndex = turnIndex;
    }

    public List<DialogueEntity> getEntityStack() {
        return entityStack;
    }

    public void setEntityStack(List<DialogueEntity> entityStack) {
        this.entityStack = entityStack != null ? entityStack : new ArrayList<>();
    }

    public String getActiveTopic() {
        return activeTopic;
    }

    public void setActiveTopic(String activeTopic) {
        this.activeTopic = activeTopic != null ? activeTopic : "";
    }

    public int getTopicDepth() {
        return topicDepth;
    }

    public void setTopicDepth(int topicDepth) {
        this.topicDepth = topicDepth;
    }

    public int getExploredNodes() {
        return exploredNodes;
    }

    public void setExploredNodes(int exploredNodes) {
        this.exploredNodes = exploredNodes;
    }

    public boolean isTopicExhausted() {
        return topicExhausted;
    }

    public void setTopicExhausted(boolean topicExhausted) {
        this.topicExhausted = topicExhausted;
    }

    public int getShortReplyCount() {
        return shortReplyCount;
    }

    public void setShortReplyCount(int shortReplyCount) {
        this.shortReplyCount = shortReplyCount;
    }

    public int getLastRecoveryTurn() {
        return lastRecoveryTurn;
    }

    public void setLastRecoveryTurn(int lastRecoveryTurn) {
        this.lastRecoveryTurn = lastRecoveryTurn;
    }

    public int getRecoveryStep() {
        return recoveryStep;
    }

    public void setRecoveryStep(int recoveryStep) {
        this.recoveryStep = recoveryStep;
    }

    public List<String> getLastQuestionTypes() {
        return lastQuestionTypes;
    }

    public void setLastQuestionTypes(List<String> lastQuestionTypes) {
        this.lastQuestionTypes = lastQuestionTypes != null ? lastQuestionTypes : new ArrayList<>();
    }

    public List<String> getFavoriteTopics() {
        return favoriteTopics;
    }

    public void setFavoriteTopics(List<String> favoriteTopics) {
        this.favoriteTopics = favoriteTopics != null ? favoriteTopics : new ArrayList<>();
    }

    public Set<String> getMentionedEntities() {
        return mentionedEntities;
    }

    public void setMentionedEntities(Set<String> mentionedEntities) {
        this.mentionedEntities = mentionedEntities != null ? mentionedEntities : new LinkedHashSet<>();
    }

    public String getLastResolvedPronoun() {
        return lastResolvedPronoun;
    }

    public void setLastResolvedPronoun(String lastResolvedPronoun) {
        this.lastResolvedPronoun = lastResolvedPronoun;
    }

    public String getLastResolvedReferent() {
        return lastResolvedReferent;
    }

    public void setLastResolvedReferent(String lastResolvedReferent) {
        this.lastResolvedReferent = lastResolvedReferent;
    }

    public DialogueEntity activeEntity() {
        if (entityStack == null || entityStack.isEmpty()) {
            return null;
        }
        return entityStack.get(entityStack.size() - 1);
    }

    public String activeEntityName() {
        DialogueEntity e = activeEntity();
        return e != null && e.getName() != null ? e.getName() : "";
    }

    public String parentEntityName() {
        if (entityStack == null || entityStack.size() < 2) {
            DialogueEntity e = activeEntity();
            return e != null && e.getParent() != null ? e.getParent() : "";
        }
        return entityStack.get(entityStack.size() - 2).getName();
    }
}
