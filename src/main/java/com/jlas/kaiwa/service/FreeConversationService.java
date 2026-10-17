package com.jlas.kaiwa.service;

import com.jlas.kaiwa.memory.MemoryAiClient;
import com.jlas.kaiwa.memory.MemoryContext;
import com.jlas.kaiwa.memory.MemoryStore;
import org.springframework.stereotype.Service;
import java.util.Map;

@Service
public class FreeConversationService {
    private final MemoryStore store;
    private final MemoryContext context;
    private final MemoryAiClient ai;
    public FreeConversationService(MemoryStore store, MemoryContext context, MemoryAiClient ai) {
        this.store = store; this.context = context; this.ai = ai;
    }

    public String chat(long userId, long sessionId, String message, Map<String, String> preferences) {
        String token = store.reserveChat(userId, sessionId);
        try {
            var session = store.session(userId, sessionId);
            String reply = ai.reply(context.messages(session, message, preferences));
            store.append(userId, sessionId, token, message, reply);
            return reply;
        } finally { store.releaseChat(userId, sessionId, token); }
    }
}
