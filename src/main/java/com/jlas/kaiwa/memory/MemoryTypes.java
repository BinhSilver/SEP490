package com.jlas.kaiwa.memory;

import java.time.LocalDateTime;
import java.util.List;

public final class MemoryTypes {
    private MemoryTypes() { }

    public record Turn(long id, String role, String content, String createdAt) { }
    public record Session(long id, long userId, String status, String summary, long revision,
                          long processedRevision, List<Turn> turns) { }
    public record Memory(long id, long userId, String key, String kind, String topic, String content,
                         int importance, long sourceSessionId, LocalDateTime updatedAt,
                         LocalDateTime expiresAt, double[] embedding, String embeddingModel, String evidence,
                         LocalDateTime observedAt, String status) {
        public Memory {
            evidence = evidence == null ? "" : evidence;
            observedAt = observedAt == null ? updatedAt : observedAt;
            status = status == null ? "ACTIVE" : status;
        }
        public Memory(long id, long userId, String key, String kind, String topic, String content,
                      int importance, long sourceSessionId, LocalDateTime updatedAt,
                      LocalDateTime expiresAt, double[] embedding, String embeddingModel) {
            this(id, userId, key, kind, topic, content, importance, sourceSessionId,
                    updatedAt, expiresAt, embedding, embeddingModel, "", updatedAt, "ACTIVE");
        }
        public Memory(long id, long userId, String key, String kind, String topic, String content,
                      int importance, long sourceSessionId, LocalDateTime updatedAt,
                      LocalDateTime expiresAt, double[] embedding, String embeddingModel, String evidence) {
            this(id, userId, key, kind, topic, content, importance, sourceSessionId,
                    updatedAt, expiresAt, embedding, embeddingModel, evidence, updatedAt, "ACTIVE");
        }
        public Memory(long id, long userId, String key, String kind, String topic, String content,
                      int importance, long sourceSessionId, LocalDateTime updatedAt,
                      LocalDateTime expiresAt, double[] embedding, String embeddingModel, String evidence, LocalDateTime observedAt) {
            this(id, userId, key, kind, topic, content, importance, sourceSessionId,
                    updatedAt, expiresAt, embedding, embeddingModel, evidence, observedAt, "ACTIVE");
        }
    }
    public record Hit(Memory memory, double score) { }
    public record Fact(String key, String topic, String content, String kind, int importance,
                       Long evidenceTurnId, String evidence, LocalDateTime expiresAt, boolean active) { }
    public record Extraction(String summary, List<Fact> facts) { }
    public record Job(Session session, String token) { }
}
