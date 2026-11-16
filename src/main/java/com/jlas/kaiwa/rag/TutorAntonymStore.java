package com.jlas.kaiwa.rag;

import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Gợi ý từ trái nghĩa của gia sư AI, lưu trong {@code tutor_antonym_suggestion} (sql/migrations/079) để người hỏi sau
 * nhận cùng một đáp án và người duyệt rà lại. DB chưa chạy migration thì mọi thao tác thành "không có gì":
 * gia sư vẫn gợi ý, chỉ không lưu.
 */
@Repository
public class TutorAntonymStore {

    private static final Logger log = LoggerFactory.getLogger(TutorAntonymStore.class);

    private final JdbcTemplate jdbc;

    public TutorAntonymStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @param antonym null khi model không đề xuất được từ nào kiểm chứng được */
    public record Saved(String status, RagLexiconLookup.WordEntry antonym) {
        public boolean approved() { return "APPROVED".equals(status); }
        public boolean rejected() { return "REJECTED".equals(status); }
    }

    /** Gợi ý đã lưu cho từ này (và ghi thêm một lượt hỏi), hoặc rỗng khi chưa ai hỏi. */
    public Optional<Saved> find(long vocabularyId) {
        try {
            List<Saved> rows = jdbc.query("SELECT s.status, v.id, v.expression, v.reading, v.meaning, v.word_type, v.jlpt_level"
                            + " FROM tutor_antonym_suggestion s LEFT JOIN vocabulary_entry v ON v.id = s.antonym_vocabulary_id"
                            + " AND v.dictionary_status = 'PUBLISHED' WHERE s.vocabulary_id = ?",
                    (rs, n) -> new Saved(rs.getString("status"), rs.getObject("id") == null ? null
                            : new RagLexiconLookup.WordEntry(rs.getLong("id"), rs.getString("expression"), rs.getString("reading"),
                            rs.getString("meaning"), rs.getString("word_type"), rs.getString("jlpt_level"))), vocabularyId);
            if (!rows.isEmpty()) jdbc.update("UPDATE tutor_antonym_suggestion SET ask_count = ask_count + 1 WHERE vocabulary_id = ?", vocabularyId);
            return rows.stream().findFirst();
        } catch (DataAccessException e) {
            log.warn("tutor_antonym_suggestion is not usable (migration 079 not applied?): {}", e.getMostSpecificCause().getMessage());
            return Optional.empty();
        }
    }

    public void save(long vocabularyId, Long antonymVocabularyId, String model) {
        try {
            jdbc.update("INSERT IGNORE INTO tutor_antonym_suggestion (vocabulary_id, antonym_vocabulary_id, model) VALUES (?, ?, ?)",
                    vocabularyId, antonymVocabularyId, model);
        } catch (DataAccessException e) {
            log.warn("Could not save the antonym suggestion: {}", e.getMostSpecificCause().getMessage());
        }
    }
}
