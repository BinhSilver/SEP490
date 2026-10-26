package com.jlas.kaiwa.rag;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

/**
 * Một đoạn giáo trình đưa vào index. {@code key} sinh từ bản ghi MySQL (vd. {@code grammar_point:12}),
 * nên id điểm trong Qdrant luôn giống nhau giữa các lần reindex.
 *
 * @param citation dữ liệu trích dẫn trả cho người dùng: giáo trình, bài, câu ví dụ, audio và mốc thời gian
 */
public record RagChunk(String key, String sourceType, long sourceId, long lessonId, long textbookId,
                       String jlptLevel, String title, String text, Map<String, Object> citation) {

    /** Đổi khi cách dựng đoạn thay đổi, để mọi điểm cũ được embed lại. */
    public static final String INDEX_VERSION = "rag-v1";

    public String pointId() {
        return UUID.nameUUIDFromBytes(("jlas-rag:" + key).getBytes(StandardCharsets.UTF_8)).toString();
    }

    /** Hash của mọi thứ ghi vào Qdrant; giống nhau thì không cần embed lại. */
    public String contentHash(String citationJson) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String part : new String[]{INDEX_VERSION, title, text, citationJson, jlptLevel, String.valueOf(lessonId)}) {
                digest.update(String.valueOf(part).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
