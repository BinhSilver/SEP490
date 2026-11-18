package com.jlas.kaiwa.rag;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Câu trả lời tầng 2 (AI tổng hợp khi J-LAS thiếu dữ liệu), lưu trong {@code tutor_ai_answer} (sql/migrations/082).
 * Cùng một câu hỏi thì mọi người nhận cùng một bản, không gọi lại AI. Thiếu bảng thì mọi thao tác thành "không có gì".
 */
@Repository
public class TutorAiAnswerStore {

    private static final Logger log = LoggerFactory.getLogger(TutorAiAnswerStore.class);

    private final JdbcTemplate jdbc;

    public TutorAiAnswerStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Saved(String status, String answer) {
        public boolean approved() { return "APPROVED".equals(status); }
        public boolean rejected() { return "REJECTED".equals(status); }
    }

    /** Câu hỏi đã chuẩn hóa: NFKC, chữ thường, gộp khoảng trắng, bỏ dấu câu ở cuối. */
    static String normalize(String question) {
        String s = Normalizer.normalize(question == null ? "" : question, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        return s.replaceAll("\\s+", " ").strip().replaceAll("[\\s?!.。？！…]+$", "");
    }

    static String hash(String question) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(normalize(question).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Bản đã lưu cho câu hỏi này (và ghi thêm một lượt hỏi), hoặc rỗng khi chưa ai hỏi. */
    public Optional<Saved> find(String question) {
        String key = hash(question);
        try {
            List<Saved> rows = jdbc.query("SELECT status, answer FROM tutor_ai_answer WHERE question_hash = ?",
                    (rs, n) -> new Saved(rs.getString("status"), rs.getString("answer")), key);
            if (!rows.isEmpty()) jdbc.update("UPDATE tutor_ai_answer SET ask_count = ask_count + 1 WHERE question_hash = ?", key);
            return rows.stream().findFirst();
        } catch (DataAccessException e) {
            log.warn("tutor_ai_answer is not usable (migration 082 not applied?): {}", e.getMostSpecificCause().getMessage());
            return Optional.empty();
        }
    }

    public void save(String question, String answer, String model) {
        try {
            String q = question.strip();
            jdbc.update("INSERT IGNORE INTO tutor_ai_answer (question_hash, question, answer, model) VALUES (?, ?, ?, ?)",
                    hash(question), q.length() > 600 ? q.substring(0, 600) : q, answer, model);
        } catch (DataAccessException e) {
            log.warn("Could not save the AI answer: {}", e.getMostSpecificCause().getMessage());
        }
    }
}
