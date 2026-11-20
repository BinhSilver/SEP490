package com.jlas.contentreport;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.List;

public final class ContentReportDtos {
    private ContentReportDtos() {}
    public enum Target { JLPT_QUESTION, VERB_QUESTION, KANJI_MAP, AI_EXPLANATION }
    public enum Page { JLPT_EXAM, JLPT_PRACTICE, VERB_PRACTICE, KANJI_MAP }
    public enum Reason { CONTENT, ANSWER, AUDIO, AI, OTHER }
    public enum Status { OPEN, RESOLVED, REJECTED }
    public record Request(@NotNull Target targetType, @NotNull Page page,
            @Size(max=80) String questionId, @Size(max=36) String sessionId,
            @Size(max=80) String character, @Size(max=20000) String explanation,
            @NotNull Reason reason, @Size(max=1000) String note) {}
    public record Resolution(@NotNull Status status, @Size(max=1000) String note) {}
    public record Saved(Long reportId) {}
    public record TargetSnapshot(Target type, String code, String label, String url, JsonNode context) {}
    public record Row(Long id, Long reporterUserId, Target targetType, String targetCode,
            Page page, String label, Reason reason, String note, JsonNode context,
            String sourceUrl, Status status, String reviewNote, Long resolvedBy,
            LocalDateTime createdAt, LocalDateTime resolvedAt) {}
    public record ResultPage(List<Row> content, int page, int size, long totalElements, long totalPages) {}
}
