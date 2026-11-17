package com.jlas.tutor;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Hàng chờ duyệt nội dung do gia sư AI tạo ra: câu trả lời tầng 2 ({@code tutor_ai_answer}, migration 082) và gợi ý từ
 * trái nghĩa ({@code tutor_antonym_suggestion}, migration 079). Người duyệt là CONTENT_CREATOR hoặc ADMIN.
 */
@Repository
public class TutorReviewQueue {

    public static final String KIND_ANSWER = "ANSWER", KIND_ANTONYM = "ANTONYM";
    static final Set<String> STATUSES = Set.of("PENDING", "APPROVED", "REJECTED");
    static final int MAX_ANSWER_CHARS = 4_000;
    private static final Pattern JAPANESE_RUN = Pattern.compile("[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}ー々]{2,}");

    private final JdbcTemplate jdbc;

    public TutorReviewQueue(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Một mục chờ duyệt. {@code word} là từ tiếng Nhật đầu tiên của câu hỏi (câu trả lời AI) hoặc từ được hỏi (từ trái
     * nghĩa), để người duyệt thấy các câu cùng một từ đứng cạnh nhau.
     */
    public record Item(String kind, long id, String status, int askCount, String model, String question, String answer,
                       String word, String wordReading, String antonym, String antonymReading, String antonymMeaning,
                       LocalDateTime updatedAt) { }

    public record Page(List<Item> items, int page, int size, long total) { }

    /** Sắp theo số lần được hỏi (câu nhiều người hỏi nhất lên trước), rồi theo từ, rồi mới nhất. */
    public Page list(String kind, String status, String search, int page, int size) {
        String like = search == null || search.isBlank() ? null : "%" + search.strip() + "%";
        int offset = page * size;
        if (KIND_ANTONYM.equals(kind)) {
            String where = " WHERE s.status = ?" + (like == null ? "" : " AND (w.expression LIKE ? OR w.reading LIKE ?)");
            Object[] args = like == null ? new Object[]{status} : new Object[]{status, like, like};
            long total = jdbc.queryForObject("SELECT COUNT(*) FROM tutor_antonym_suggestion s JOIN vocabulary_entry w"
                    + " ON w.id = s.vocabulary_id" + where, Long.class, args);
            List<Item> items = jdbc.query("SELECT s.id, s.status, s.ask_count, s.model, s.updated_at, w.expression, w.reading,"
                            + " a.expression AS a_expr, a.reading AS a_read, a.meaning AS a_meaning"
                            + " FROM tutor_antonym_suggestion s JOIN vocabulary_entry w ON w.id = s.vocabulary_id"
                            + " LEFT JOIN vocabulary_entry a ON a.id = s.antonym_vocabulary_id" + where
                            + " ORDER BY s.ask_count DESC, w.expression, s.updated_at DESC LIMIT " + size + " OFFSET " + offset,
                    (rs, n) -> new Item(KIND_ANTONYM, rs.getLong("id"), rs.getString("status"), rs.getInt("ask_count"),
                            rs.getString("model"), "Từ trái nghĩa của 「" + rs.getString("expression") + "」", null,
                            rs.getString("expression"), rs.getString("reading"), rs.getString("a_expr"), rs.getString("a_read"),
                            rs.getString("a_meaning"), time(rs.getTimestamp("updated_at"))), args);
            return new Page(items, page, size, total);
        }
        String where = " WHERE status = ?" + (like == null ? "" : " AND (question LIKE ? OR answer LIKE ?)");
        Object[] args = like == null ? new Object[]{status} : new Object[]{status, like, like};
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM tutor_ai_answer" + where, Long.class, args);
        List<Item> items = jdbc.query("SELECT id, status, ask_count, model, question, answer, updated_at FROM tutor_ai_answer"
                        + where + " ORDER BY ask_count DESC, updated_at DESC LIMIT " + size + " OFFSET " + offset,
                (rs, n) -> new Item(KIND_ANSWER, rs.getLong("id"), rs.getString("status"), rs.getInt("ask_count"),
                        rs.getString("model"), rs.getString("question"), rs.getString("answer"), firstJapanese(rs.getString("question")),
                        null, null, null, null, time(rs.getTimestamp("updated_at"))), args);
        return new Page(items, page, size, total);
    }

    /** @return false khi không có mục này */
    public boolean updateAnswer(long id, String status, String answer) {
        return jdbc.update("UPDATE tutor_ai_answer SET status = ?, answer = COALESCE(?, answer) WHERE id = ?", status, answer, id) == 1;
    }

    /**
     * @param antonymVocabularyId null = giữ từ trái nghĩa đang có
     * @return false khi không có mục này
     */
    public boolean updateAntonym(long id, String status, Long antonymVocabularyId) {
        return jdbc.update("UPDATE tutor_antonym_suggestion SET status = ?, antonym_vocabulary_id = COALESCE(?, antonym_vocabulary_id)"
                + " WHERE id = ?", status, antonymVocabularyId, id) == 1;
    }

    /** Mục từ điển công khai đầu tiên có đúng chữ này (từ trái nghĩa người duyệt gõ phải có trong từ điển J-LAS). */
    public Long dictionaryEntryId(String expression) {
        List<Long> ids = jdbc.queryForList("SELECT id FROM vocabulary_entry WHERE expression = ? AND dictionary_status = 'PUBLISHED'"
                + " ORDER BY (usage_scope <> 'TEXTBOOK') DESC, id LIMIT 1", Long.class, expression.strip());
        return ids.isEmpty() ? null : ids.getFirst();
    }

    static String firstJapanese(String text) {
        Matcher m = JAPANESE_RUN.matcher(text == null ? "" : text);
        return m.find() ? m.group() : null;
    }

    private static LocalDateTime time(Timestamp t) {
        return t == null ? null : t.toLocalDateTime();
    }
}
