package com.jlas.kaiwa.model;

/**
 * Shared markers for free-chat (AI Tán Gẫu) mode.
 * Free chat reuses the same voice/call engine as scenario mode;
 * only the system prompt and learning UI differ.
 */
public final class FreeChatSupport {

    public static final String SCENARIO_SLUG = "free-chat";
    public static final String CATEGORY_CODE = "system_free_chat";
    public static final String PROMPT_NAME = "Free Chat Natural Japanese";

    private FreeChatSupport() {}

    public static boolean isFreeChat(Scenario scenario) {
        return scenario != null && SCENARIO_SLUG.equals(scenario.getSlug());
    }

    public static boolean isFreeChat(ConversationSession session) {
        return session != null && isFreeChat(session.getScenario());
    }
}
