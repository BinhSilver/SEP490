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
                         LocalDateTime expiresAt, double[] embedding, String embeddingModel) { }
    public record Hit(Memory memory, double score) { }
    public record Fact(String key, String topic, String content, String kind, int importance,
                       Long evidenceTurnId, String evidence, LocalDateTime expiresAt, boolean active) { }
    public record Extraction(String summary, List<Fact> facts) { }
    public record Job(Session session, String token) { }
}
