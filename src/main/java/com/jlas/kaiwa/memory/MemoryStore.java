package com.jlas.kaiwa.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static com.jlas.kaiwa.memory.MemoryTypes.*;

@Repository
public class MemoryStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final MemoryProperties properties;
    public MemoryStore(JdbcTemplate jdbc, ObjectMapper json, MemoryProperties properties) {
        this.jdbc = jdbc; this.json = json; this.properties = properties;
    }

    public long create(long userId) {
        var key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("INSERT INTO kaiwa_free_session(user_id,summary,recent_turns) VALUES (?,'','[]')", new String[]{"id"});
            statement.setLong(1, userId); return statement;
        }, key);
        return key.getKey().longValue();
    }

    public Session session(long userId, long sessionId) {
        return load(userId, sessionId, false);
    }

    private Session load(long userId, long id, boolean lock) {
        return jdbc.query("SELECT * FROM kaiwa_free_session WHERE id=? AND user_id=?" + (lock ? " FOR UPDATE" : ""),
                (rs, n) -> new Session(rs.getLong("id"), rs.getLong("user_id"), rs.getString("status"),
                        rs.getString("summary"), rs.getLong("revision"), rs.getLong("processed_revision"), turns(rs.getString("recent_turns"))), id, userId)
                .stream().findFirst().orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy phiên hội thoại."));
    }

    @Transactional
    public String reserveChat(long userId, long id) {
        Session session = load(userId, id, true);
        if (!"IN_PROGRESS".equals(session.status())) throw new ApiException(ErrorCode.CONFLICT, "Phiên hội thoại đã kết thúc.");
        if (session.turns().stream().filter(t -> t.id() > session.processedRevision())
                .mapToInt(t -> t.content().length()).sum() > 80_000)
            throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE, "Bộ nhớ đang xử lý hội thoại, vui lòng thử lại sau.");
        String token = UUID.randomUUID().toString();
        LocalDateTime started = LocalDateTime.now();
        int changed = jdbc.update("UPDATE kaiwa_free_session SET chat_token=?,chat_started_at=?,chat_lease_until=? WHERE id=? AND user_id=? AND (chat_token IS NULL OR chat_lease_until<?)",
                token, started, started.plusMinutes(3), id, userId, started);
        if (changed == 0) throw new ApiException(ErrorCode.CONFLICT, "AI đang trả lời tin nhắn trước.");
        return token;
    }

    @Transactional
    public void append(long userId, long id, String token, String user, String assistant) {
        Session session = load(userId, id, true);
        LocalDateTime started = jdbc.queryForObject("SELECT chat_started_at FROM kaiwa_free_session WHERE id=?", LocalDateTime.class, id);
        List<Turn> turns = new ArrayList<>(session.turns());
        String now = LocalDateTime.now().toString();
        turns.add(new Turn(session.revision() + 1, "user", user, started == null ? now : started.toString()));
        turns.add(new Turn(session.revision() + 2, "assistant", assistant, now));
        int count = jdbc.update("UPDATE kaiwa_free_session SET recent_turns=?,revision=revision+2,updated_at=?,chat_token=NULL,chat_started_at=NULL,chat_lease_until=NULL WHERE id=? AND user_id=? AND chat_token=? AND status='IN_PROGRESS'",
                encode(turns), LocalDateTime.now(), id, userId, token);
        if (count == 0) throw new ApiException(ErrorCode.CONFLICT, "Phiên đã thay đổi; vui lòng tải lại.");
    }

    public void releaseChat(long userId, long id, String token) {
        jdbc.update("UPDATE kaiwa_free_session SET chat_token=NULL,chat_started_at=NULL,chat_lease_until=NULL WHERE id=? AND user_id=? AND chat_token=?", id, userId, token);
    }

    /** Import committed AI Call transcript entries; the bridge owns the transcript cursor. */
    @Transactional
    public void importTurns(long userId, long id, List<Turn> incoming) {
        if (incoming.isEmpty()) return;
        Session session = load(userId, id, true);
        List<Turn> turns = new ArrayList<>(session.turns());
        long revision = session.revision();
        for (Turn turn : incoming)
            turns.add(new Turn(++revision, turn.role(), turn.content(), turn.createdAt()));
        jdbc.update("UPDATE kaiwa_free_session SET recent_turns=?,revision=?,updated_at=? WHERE id=? AND user_id=?",
                encode(turns), revision, LocalDateTime.now(), id, userId);
    }

    @Transactional
    public void end(long userId, long id) {
        load(userId, id, true);
        int changed = jdbc.update("UPDATE kaiwa_free_session SET status='COMPLETED',ended_at=COALESCE(ended_at,?),retry_at=NULL WHERE id=? AND user_id=? AND (chat_token IS NULL OR chat_lease_until<?)",
                LocalDateTime.now(), id, userId, LocalDateTime.now());
        if (changed == 0) throw new ApiException(ErrorCode.CONFLICT, "Đợi AI trả lời xong trước khi kết thúc.");
    }

    @Transactional
    public Job claim() {
        LocalDateTime now = LocalDateTime.now();
        // Recover expired worker leases after a restart. Checkpoint idle and abandoned sessions too.
        List<Map<String, Object>> candidates = jdbc.queryForList("""
                SELECT id,user_id FROM kaiwa_free_session
                WHERE revision>processed_revision AND (retry_at IS NULL OR retry_at<=?)
                AND (memory_token IS NULL OR memory_lease_until<?)
                AND (status='COMPLETED' OR revision-processed_revision>=? OR updated_at<?)
                ORDER BY updated_at,id LIMIT 1 FOR UPDATE SKIP LOCKED
                """, now, now, properties.checkpointTurns() * 2, now.minusSeconds(60));
        if (candidates.isEmpty()) return null;
        long id = ((Number) candidates.getFirst().get("id")).longValue();
        long user = ((Number) candidates.getFirst().get("user_id")).longValue();
        String token = UUID.randomUUID().toString();
        jdbc.update("UPDATE kaiwa_free_session SET memory_token=?,memory_lease_until=? WHERE id=?", token, now.plusMinutes(10), id);
        return new Job(load(user, id, false), token);
    }

    @Transactional
    public void saveExtraction(Job job, Extraction extraction, Map<String, double[]> embeddings, String model) {
        Session snapshot = job.session();
        // Serialize updates of facts across sessions belonging to the same user.
        jdbc.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE", snapshot.userId());
        Session current = load(snapshot.userId(), snapshot.id(), true);
        String token = jdbc.queryForObject("SELECT memory_token FROM kaiwa_free_session WHERE id=?", String.class, snapshot.id());
        if (!job.token().equals(token)) return;
        for (Fact fact : extraction.facts()) {
            Turn source = snapshot.turns().stream().filter(t -> t.id() == fact.evidenceTurnId()).findFirst().orElseThrow();
            upsert(snapshot.userId(), snapshot.id(), fact.key(), fact.kind(), fact.topic(), fact.content(), fact.importance(),
                    fact.evidenceTurnId(), fact.evidence(), LocalDateTime.parse(source.createdAt()), fact.expiresAt(), fact.active(), embeddings.get(fact.key()), model);
        }
        if (!extraction.summary().isBlank()) {
            String key = "session." + snapshot.id();
            upsert(snapshot.userId(), snapshot.id(), key, "SUMMARY", "Tóm tắt phiên", extraction.summary(), 2,
                    null, null, LocalDateTime.now(), null, true, embeddings.get(key), model);
        }
        // Preserve turns that arrived while the worker was making network calls.
        List<Turn> compacted = current.turns().stream().filter(t -> t.id() > snapshot.revision()
                || t.id() > snapshot.revision() - properties.recentMessages()).toList();
        jdbc.update("UPDATE kaiwa_free_session SET summary=?,recent_turns=?,processed_revision=?,memory_token=NULL,memory_lease_until=NULL,retry_at=NULL WHERE id=? AND memory_token=?",
                extraction.summary(), encode(compacted), snapshot.revision(), snapshot.id(), job.token());
    }

    private void upsert(long userId, long sessionId, String key, String kind, String topic, String content, int importance,
                        Long evidenceId, String evidence, LocalDateTime observed, LocalDateTime expires,
                        boolean active, double[] embedding, String model) {
        List<Map<String, Object>> prior = jdbc.queryForList("SELECT id,observed_at FROM kaiwa_memory WHERE user_id=? AND memory_key=? FOR UPDATE", userId, key);
        String status = active ? "ACTIVE" : "INACTIVE";
        Object vector = embedding == null ? null : encode(embedding);
        String vectorModel = embedding == null ? null : model;
        if (prior.isEmpty()) {
            jdbc.update("""
                    INSERT INTO kaiwa_memory(user_id,memory_key,kind,topic,content,importance,source_session_id,evidence_turn_id,evidence,status,observed_at,expires_at,embedding,embedding_model)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, userId, key, kind, topic, content, importance, sessionId, evidenceId, evidence, status, observed, expires, vector, vectorModel);
        } else {
            long id = ((Number) prior.getFirst().get("id")).longValue();
            // A slow older session must not overwrite a newer fact or resurrect a deleted memory.
            jdbc.update("""
                    UPDATE kaiwa_memory SET kind=?,topic=?,content=?,importance=?,source_session_id=?,evidence_turn_id=?,evidence=?,status=?,observed_at=?,expires_at=?,embedding=?,embedding_model=?,embedding_retry_at=NULL,updated_at=?
                    WHERE id=? AND observed_at<?
                    """, kind, topic, content, importance, sessionId, evidenceId, evidence, status, observed, expires, vector, vectorModel, LocalDateTime.now(), id, observed);
        }
    }

    public void fail(Job job) {
        jdbc.update("UPDATE kaiwa_free_session SET memory_token=NULL,memory_lease_until=NULL,retry_at=? WHERE id=? AND memory_token=?",
                LocalDateTime.now().plusMinutes(1), job.session().id(), job.token());
    }

    public List<Memory> memories(long userId) {
        // No latest-N truncation: old relevant sessions remain searchable.
        return jdbc.query("SELECT * FROM kaiwa_memory WHERE user_id=? AND status='ACTIVE' AND (expires_at IS NULL OR expires_at>?)", this::memory, userId, LocalDateTime.now());
    }

    public List<Memory> missingEmbeddings(String model) {
        return jdbc.query("SELECT * FROM kaiwa_memory WHERE status='ACTIVE' AND (expires_at IS NULL OR expires_at>?) AND (embedding IS NULL OR embedding_model<>?) AND (embedding_retry_at IS NULL OR embedding_retry_at<?) ORDER BY id LIMIT 3",
                this::memory, LocalDateTime.now(), model, LocalDateTime.now());
    }

    public void saveEmbedding(Memory memory, double[] vector, String model) {
        jdbc.update("UPDATE kaiwa_memory SET embedding=?,embedding_model=?,embedding_retry_at=NULL WHERE id=? AND user_id=? AND content=? AND updated_at=? AND status='ACTIVE'",
                encode(vector), model, memory.id(), memory.userId(), memory.content(), memory.updatedAt());
    }

    public void failEmbedding(long id) {
        jdbc.update("UPDATE kaiwa_memory SET embedding_retry_at=? WHERE id=?", LocalDateTime.now().plusMinutes(5), id);
    }

    public void forget(long userId, long id) {
        if (jdbc.update("UPDATE kaiwa_memory SET status='DELETED',observed_at=?,updated_at=?,embedding=NULL,embedding_model=NULL WHERE id=? AND user_id=?",
                LocalDateTime.now(), LocalDateTime.now(), id, userId) == 0)
            throw new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy ký ức.");
    }

    private Memory memory(ResultSet rs, int index) throws SQLException {
        double[] vector = null;
        try { if (rs.getString("embedding") != null) vector = json.readValue(rs.getString("embedding"), double[].class); }
        catch (Exception ignored) { /* Corrupt vector cannot stop keyword retrieval. */ }
        Timestamp expires = rs.getTimestamp("expires_at");
        return new Memory(rs.getLong("id"), rs.getLong("user_id"), rs.getString("memory_key"), rs.getString("kind"), rs.getString("topic"),
                rs.getString("content"), rs.getInt("importance"), rs.getLong("source_session_id"), rs.getTimestamp("updated_at").toLocalDateTime(),
                expires == null ? null : expires.toLocalDateTime(), vector, rs.getString("embedding_model"));
    }

    private List<Turn> turns(String value) {
        try { return json.readValue(value, new TypeReference<List<Turn>>() { }); }
        catch (Exception e) { throw new IllegalStateException("Invalid stored conversation"); }
    }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("Cannot serialize conversation memory"); }
    }
}
