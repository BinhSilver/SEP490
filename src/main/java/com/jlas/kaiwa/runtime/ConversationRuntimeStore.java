package com.jlas.kaiwa.runtime;

import java.util.Optional;

/** Storage boundary for active-call state; can later be backed by Redis. */
public interface ConversationRuntimeStore {
    Optional<ConversationRuntimeState> get(Long sessionId);
    void put(ConversationRuntimeState state);
    void remove(Long sessionId);
    long size();
}
