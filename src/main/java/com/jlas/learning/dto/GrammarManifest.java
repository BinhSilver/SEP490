package com.jlas.learning.dto;

import java.util.List;

/**
 * [JLAS-W0108] P2 — manifest tài nguyên của một activity ngữ pháp: chỉ chứa đúng mẫu đã chọn
 * (pattern/meaning/example/note) cùng nguồn sách/bài/content_item và một {@code curriculumVersion}
 * ổn định để reload không nhân đôi. Mẫu không ánh xạ được thì trả {@code readiness} =
 * {@code BLOCKED_CONTENT} kèm lý do, không âm thầm lấy cả bài thay thế.
 */
public record GrammarManifest(
        Long grammarPointId,
        String pattern,
        String meaning,
        String example,
        String note,
        Long sourceLessonId,
        Long textbookId,
        Long unitId,
        String level,
        String curriculumVersion,
        String readiness,
        String blockReason,
        List<ExampleBlock> examples) {

    public static final String READY = "READY";
    public static final String BLOCKED_CONTENT = "BLOCKED_CONTENT";

    public static GrammarManifest blocked(Long grammarPointId, String reason) {
        return new GrammarManifest(grammarPointId, null, null, null, null, null, null, null, null,
                null, BLOCKED_CONTENT, reason, List.of());
    }

    public boolean ready() {
        return READY.equals(readiness);
    }

    /** Một ví dụ có nguồn (content_item id) để truy vết provenance của từng block. */
    public record ExampleBlock(Long contentItemId, String title, String content,
            String translatedText, int orderIndex) { }
}
