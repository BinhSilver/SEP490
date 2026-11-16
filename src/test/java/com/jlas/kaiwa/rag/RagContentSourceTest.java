package com.jlas.kaiwa.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;

/**
 * Chỉ nội dung được phép mới vào index: bài PUBLISHED của giáo trình PUBLISHED, không có nguồn vnjpclub,
 * không phải nội dung AI chưa duyệt. Tra cứu từ/kanji bỏ từ do vnjpclub thêm mới.
 */
@DataJpaTest(properties = {"spring.profiles.include=",
        "spring.datasource.url=jdbc:h2:mem:rag-content-source;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"})
@ContextConfiguration(classes = RagContentSourceTest.Cfg.class)
class RagContentSourceTest {
    @Configuration(proxyBeanMethods = false)
    @EntityScan("com.jlas")
    @EnableJpaRepositories(basePackages = "com.jlas.dictionary.repository")
    static class Cfg { }

    @Autowired JdbcTemplate jdbc;

    private static RagProperties properties(boolean includeVnjpclub, boolean includeUnreviewed) {
        return new RagProperties(true, "http://127.0.0.1:6333", "", "jlas_content", 16, 5, 0.45, includeVnjpclub, includeUnreviewed, 1_000);
    }

    @BeforeEach
    void seed() {
        // content_source không có entity; tạo theo đúng sql/migrations/018_content_source.sql (rút gọn).
        jdbc.execute("CREATE TABLE IF NOT EXISTS content_source (id BIGINT AUTO_INCREMENT PRIMARY KEY, entity_type VARCHAR(30) NOT NULL,"
                + " entity_id BIGINT NOT NULL, source_code VARCHAR(30) NOT NULL, stable_code VARCHAR(255) NOT NULL,"
                + " review_status VARCHAR(20) NOT NULL DEFAULT 'CHUA_DUYET', note VARCHAR(1000))");
        jdbc.update("INSERT INTO tag (id, code, name, tag_type, created_at) VALUES (1, 'n5', 'N5', 'LEVEL', CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO content_voice (code, name, is_default) VALUES ('chuan-nu', 'Chuẩn nữ', TRUE)");
        // 1: Sekai (PUBLISHED), 2: giáo trình vnjpclub, 3: AI chưa duyệt, 4: AI đã duyệt, 5: DRAFT
        for (long t = 1; t <= 5; t++) {
            jdbc.update("INSERT INTO textbook (id, title, jlpt_level, status, version, is_learning_path_source, created_at, updated_at)"
                    + " VALUES (?, ?, 'N5', ?, 1, FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", t, "Giáo trình " + t, t == 5 ? "DRAFT" : "PUBLISHED");
            jdbc.update("INSERT INTO unit (id, textbook_id, title, order_index) VALUES (?, ?, ?, 1)", t, t, "Phần " + t);
            lesson(t * 10, t, "PUBLISHED");
        }
        lesson(11, 1, "DRAFT");
        jdbc.update("INSERT INTO content_source (entity_type, entity_id, source_code, stable_code, review_status) VALUES"
                + " ('TEXTBOOK', 2, 'VNJPCLUB', 'X', 'CHUA_DUYET'), ('TEXTBOOK', 3, 'AI_GENERATED', 'N3', 'CHUA_DUYET'),"
                + " ('TEXTBOOK', 4, 'AI_GENERATED', 'N4', 'DA_DUYET')");

        for (long lesson : List.of(10L, 11L, 20L, 30L, 40L, 50L)) grammar(lesson * 10, lesson, "パターン" + lesson);
        // Một câu ví dụ riêng trong bài 10 bị gắn nguồn vnjpclub: câu đó bị bỏ, mẫu ngữ pháp vẫn giữ.
        jdbc.update("INSERT INTO content_source (entity_type, entity_id, source_code, stable_code, review_status)"
                + " VALUES ('CONTENT_ITEM', 1002, 'VNJPCLUB', 'Y', 'NGUON')");
        jdbc.update("INSERT INTO lesson_section (id, lesson_id, title, section_type, order_index) VALUES (500, 10, 'Hội thoại', 'EXAMPLE', 2)");
        jdbc.update("INSERT INTO dialogue_role (id, lesson_id, code, name_vi, name_ja) VALUES (1, 10, 'tanaka', 'Tanaka', 'たなか')");
        for (int i = 1; i <= 12; i++) {
            long id = 9000 + i;
            jdbc.update("INSERT INTO content_item (id, section_id, content_type, title, content, translated_text, order_index)"
                    + " VALUES (?, 500, 'LISTENING', 'Tanaka', ?, ?, ?)", id, "せりふ" + i, "Lời thoại " + i, i);
            jdbc.update("INSERT INTO dialogue_line_detail (content_item_id, dialogue_role_id) VALUES (?, 1)", id);
            jdbc.update("INSERT INTO content_audio (owner_type, owner_id, voice_code, audio_url, start_ms, end_ms, created_at)"
                    + " VALUES ('CONTENT_ITEM', ?, 'chuan-nu', 'sekai/hoithoai.mp3', ?, ?, CURRENT_TIMESTAMP)", id, i * 1000, i * 1000 + 900);
        }
    }

    private void lesson(long id, long unit, String status) {
        jdbc.update("INSERT INTO lesson (id, unit_id, knowledge_tag_id, title, order_index, lesson_type, skill, is_review, status, created_at, updated_at)"
                + " VALUES (?, ?, 1, ?, 1, 'GRAMMAR', 'GRAMMAR', FALSE, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", id, unit, "Bài " + id, status);
        jdbc.update("INSERT INTO lesson_section (id, lesson_id, title, section_type, order_index) VALUES (?, ?, 'Giải thích', 'EXPLANATION', 1)", id, id);
    }

    private void grammar(long id, long lesson, String pattern) {
        jdbc.update("INSERT INTO grammar_point (id, lesson_id, pattern, meaning, example, note, order_index) VALUES (?, ?, ?, 'Nghĩa', 'Ví dụ', 'Lưu ý', 1)",
                id, lesson, pattern);
        for (int i = 1; i <= 2; i++) {
            jdbc.update("INSERT INTO content_item (id, section_id, content_type, grammar_point_id, title, content, translated_text, order_index)"
                    + " VALUES (?, ?, 'GRAMMAR', ?, 'Ví dụ', ?, 'Câu dịch', ?)", id * 10 + i, lesson, id, "れいぶん" + id + "-" + i, i);
        }
    }

    @Test
    void indexesOnlyPublishedLicensedAndReviewedContent() {
        List<RagChunk> chunks = new RagContentSource(jdbc, properties(false, false)).load(null);

        assertThat(chunks).extracting(RagChunk::key).containsExactlyInAnyOrder(
                "grammar_point:100", "grammar_point:400", "section:500:DIALOGUE:0", "section:500:DIALOGUE:1");
    }

    @Test
    void grammarChunkCarriesExamplesExceptExcludedOnes() {
        RagChunk grammar = new RagContentSource(jdbc, properties(false, false)).load(null).stream()
                .filter(c -> c.key().equals("grammar_point:100")).findFirst().orElseThrow();

        assertThat(grammar.text()).contains("パターン10", "れいぶん100-1").doesNotContain("れいぶん100-2");
        assertThat(grammar.citation()).containsEntry("lessonId", 10L).containsEntry("textbook", "Giáo trình 1");
    }

    @Test
    @SuppressWarnings("unchecked")
    void dialogueIsSplitIntoWindowsWithSpeakerAndAudioTimestamps() {
        List<RagChunk> dialogue = new RagContentSource(jdbc, properties(false, false)).load(null).stream()
                .filter(c -> c.sourceType().equals("DIALOGUE")).toList();

        assertThat(dialogue).hasSize(2);
        List<Map<String, Object>> lines = (List<Map<String, Object>>) dialogue.getFirst().citation().get("lines");
        assertThat(lines).hasSize(RagContentSource.LINES_PER_CHUNK);
        assertThat(lines.getFirst()).containsEntry("speaker", "たなか").containsEntry("ja", "せりふ1")
                .containsEntry("audioUrl", "sekai/hoithoai.mp3").containsEntry("startMs", 1000).containsEntry("endMs", 1900);
        assertThat(dialogue.getFirst().text()).contains("たなか: せりふ1 — Lời thoại 1");
    }

    @Test
    void flagsCanOptIntoVnjpclubAndUnreviewedContent() {
        List<String> keys = new RagContentSource(jdbc, properties(true, true)).load(null).stream().map(RagChunk::key).toList();

        assertThat(keys).contains("grammar_point:200", "grammar_point:300").doesNotContain("grammar_point:110", "grammar_point:500");
    }

    @Test
    void lessonScopeLoadsOnlyThatLesson() {
        assertThat(new RagContentSource(jdbc, properties(false, false)).load(40L)).extracting(RagChunk::key)
                .containsExactly("grammar_point:400");
        assertThat(new RagContentSource(jdbc, properties(false, false)).load(11L)).isEmpty();
    }

    @Test
    void lexiconFindsExactWordsAndKanjiButNotWordsAddedByVnjpclub() {
        jdbc.update("INSERT INTO kanji (id, `character`, unicode_code, han_viet, jlpt_level, created_at, updated_at) VALUES"
                + " (1, '勉', '52c9', 'Miễn', 'N5', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),"
                + " (2, '強', '5f37', 'Cường', 'N5', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO kanji_reading (kanji_id, reading_type, reading_text, is_common) VALUES"
                + " (1, 'ON', 'ベン', TRUE), (2, 'ON', 'キョウ', TRUE), (2, 'KUN', 'つよ.い', TRUE)");
        word(1, "勉強", "べんきょう", "việc học");
        word(2, "勉強家", "べんきょうか", "người chăm học");
        jdbc.update("INSERT INTO content_source (entity_type, entity_id, source_code, stable_code, review_status, note) VALUES"
                + " ('VOCABULARY', 1, 'VNJPCLUB', 'A', 'NGUON', 'Dung lai tu da co (khong sua)'),"
                + " ('VOCABULARY', 2, 'VNJPCLUB', 'B', 'NGUON', 'Tu moi tu vnjpclub')");

        RagLexiconLookup.Result result = new RagLexiconLookup(jdbc, properties(false, false)).lookup("「勉強する」và 勉強家 nghĩa là gì?");

        assertThat(result.words()).extracting(RagLexiconLookup.WordEntry::expression).containsExactly("勉強");
        assertThat(result.kanji()).extracting(RagLexiconLookup.KanjiEntry::character).containsExactly("勉", "強");
        RagLexiconLookup.KanjiEntry strong = result.kanji().get(1);
        assertThat(strong.hanViet()).isEqualTo("Cường");
        assertThat(strong.onyomi()).containsExactly("キョウ");
        assertThat(strong.kunyomi()).containsExactly("つよ.い");
    }

    private void word(long id, String expression, String reading, String meaning) {
        jdbc.update("INSERT INTO vocabulary_entry (id, expression, normalized_expression, reading, normalized_reading, meaning, jlpt_level,"
                + " usage_scope, dictionary_status, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, 'N5', 'TEXTBOOK', 'PUBLISHED',"
                + " CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", id, expression, expression, reading, reading, meaning);
    }
}
