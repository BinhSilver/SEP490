package com.jlas.kaiwa.memory;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.kaiwa.model.ConversationSession;
import com.jlas.kaiwa.model.FreeChatSupport;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import static com.jlas.kaiwa.memory.MemoryTypes.*;

/** Durable memory attached to real AI Call sessions, without replacing their routes or transcript. */
@Service
public class ConversationMemoryBridge {
    private final JdbcTemplate jdbc;
    private final MemoryStore store;
    private final MemoryContext context;

    public ConversationMemoryBridge(JdbcTemplate jdbc, MemoryStore store, MemoryContext context) {
        this.jdbc = jdbc; this.store = store; this.context = context;
    }

    @Transactional
    public void restore(ConversationSession session) {
        if (!FreeChatSupport.isFreeChat(session)) return;
        Link link = link(session);
        if (link.engineState() != null) session.setEngineStateJson(link.engineState());
    }

    @Transactional
    public MemoryContext.Context context(ConversationSession session, String query) {
        Link link = link(session);
        synchronize(session, link);
        return context.retrieve(store.session(session.getUserId(), link.memorySessionId()), query);
    }

    @Transactional
    public void checkpoint(ConversationSession session) {
        if (!FreeChatSupport.isFreeChat(session)) return;
        Link link = link(session);
        synchronize(session, link);
        if (session.getEngineStateJson() != null)
            jdbc.update("UPDATE kaiwa_conversation_memory SET engine_state_json=? WHERE conversation_session_id=? AND user_id=?",
                    session.getEngineStateJson(), session.getId(), session.getUserId());
    }

    @Transactional
    public void finish(ConversationSession session) {
        if (!FreeChatSupport.isFreeChat(session)) return;
        Link link = link(session);
        synchronize(session, link);
        store.end(session.getUserId(), link.memorySessionId());
    }

    private Link link(ConversationSession session) {
        if (!FreeChatSupport.isFreeChat(session)) throw new ApiException(ErrorCode.BAD_REQUEST);
        // Lock the real session first, so chat, end and lazy backfill share one lock order.
        if (jdbc.queryForList("SELECT id FROM conversation_session WHERE id=? AND user_id=? FOR UPDATE",
                session.getId(), session.getUserId()).isEmpty())
            throw new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy phiên hội thoại.");
        List<Link> links = jdbc.query("SELECT * FROM kaiwa_conversation_memory WHERE conversation_session_id=? AND user_id=?",
                (rs, n) -> new Link(rs.getLong("memory_session_id"), rs.getLong("last_message_id"), rs.getString("engine_state_json")),
                session.getId(), session.getUserId());
        if (!links.isEmpty()) return links.getFirst();
        long memoryId = store.create(session.getUserId());
        jdbc.update("INSERT INTO kaiwa_conversation_memory(conversation_session_id,user_id,memory_session_id) VALUES (?,?,?)",
                session.getId(), session.getUserId(), memoryId);
        return new Link(memoryId, 0, null);
    }

    private void synchronize(ConversationSession session, Link link) {
        List<Turn> turns = jdbc.query("""
                SELECT id,role,content,created_at FROM conversation_message
                WHERE session_id=? AND id>? AND role IN ('user','assistant') ORDER BY id
                """, (rs, n) -> new Turn(rs.getLong("id"), rs.getString("role"), rs.getString("content"),
                rs.getObject("created_at", java.time.LocalDateTime.class).toString()), session.getId(), link.lastMessageId());
        if (turns.isEmpty()) return;
        store.importTurns(session.getUserId(), link.memorySessionId(), turns);
        jdbc.update("UPDATE kaiwa_conversation_memory SET last_message_id=? WHERE conversation_session_id=? AND user_id=?",
                turns.getLast().id(), session.getId(), session.getUserId());
    }

    private record Link(long memorySessionId, long lastMessageId, String engineState) { }
}
