package com.jlas.kaiwa.service.dialogue;

/**
 * Free-chat conversation phase (backend-owned; not scenario ConversationState entity).
 */
public enum DialoguePhase {
    GREETING,
    ICE_BREAKING,
    TOPIC_DISCOVERY,
    TOPIC_EXPLORATION,
    DEEP_DISCUSSION,
    TOPIC_TRANSITION,
    WRAP_UP,
    ENDING
}
