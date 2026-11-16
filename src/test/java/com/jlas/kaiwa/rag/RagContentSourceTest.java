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
        jdbc.execute("CREATE TABLE IF NOT EXISTS jpath_grammar (code VARCHAR(24) PRIMARY KEY, level VARCHAR(2), unit_no INT,"
                + " sort_order INT, pattern VARCHAR(120), pattern_variants_json VARCHAR(500), meaning_vi VARCHAR(1000),"
                + " formation VARCHAR(255), usage_vi VARCHAR(1000), nuance_vi VARCHAR(1000), common_mistakes_vi VARCHAR(1000),"
                + " status VARCHAR(12), report_hidden INT)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS jpath_example (code VARCHAR(24) PRIMARY KEY, owner_type VARCHAR(8),"
                + " owner_code VARCHAR(24), sort_order INT, ja VARCHAR(255), vi VARCHAR(500), status VARCHAR(12), report_hidden INT)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS jpath_vocabulary (code VARCHAR(24) PRIMARY KEY, expression VARCHAR(120),"
                + " reading VARCHAR(120), status VARCHAR(12), report_hidden INT)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS jpath_kanji (code VARCHAR(24) PRIMARY KEY, `character` VARCHAR(4),"
                + " status VARCHAR(12), report_hidden INT)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS jpath_confusable_pair (item_type VARCHAR(8), code_a VARCHAR(24),"
                + " code_b VARCHAR(24), level VARCHAR(2), diff_vi VARCHAR(600), examples_json VARCHAR(2000))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS cpath_situation (code VARCHAR(24) PRIMARY KEY, title_vi VARCHAR(200), level VARCHAR(2))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS cpath_line (situation_code VARCHAR(24), line_order INT,"
                + " speaker VARCHAR(40), ja VARCHAR(500), vi VARCHAR(500), furigana VARCHAR(800))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS cpath_item (code VARCHAR(32) PRIMARY KEY, situation_code VARCHAR(24),"
                + " kind VARCHAR(8), sort_order INT, ja VARCHAR(255), reading VARCHAR(255), vi VARCHAR(500), note_vi VARCHAR(500))");
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
    @SuppressWarnings("unchecked")
    void jpathGrammarRespectsStatusAndHiddenFlagsForGrammarAndExamples() {
        jgrammar("ok", "AI_CHECKED", 0);
        jgrammar("hold", "HOLD", 0);
        jgrammar("hidden", "REVIEWED", 1);
        jgrammar("draft", "DRAFT", 0);
        jexample("a", "ok", "REVIEWED", 0, 1);
        jexample("b", "ok", "HOLD", 0, 2);
        jexample("c", "ok", "REVIEWED", 1, 3);
        jexample("d", "ok", "DRAFT", 0, 4);
        RagChunk chunk = new RagContentSource(jdbc, properties(false, false)).load(null).stream()
                .filter(c -> c.key().equals("jpath_grammar:ok")).findFirst().orElseThrow();
        assertThat(chunk.text()).contains("Mẫu ngữ pháp (JLPT N3)", "Biến thể:", "Ý nghĩa:", "Cách chia:", "Cách dùng:",
                "Sắc thái:", "Lỗi hay gặp:", "例(れい)a").doesNotContain("れい)b", "れい)c", "れい)d");
        assertThat(chunk.citation()).containsEntry("type", "JLPT_GRAMMAR").containsEntry("code", "ok");
        assertThat(((List<Map<String, Object>>) chunk.citation().get("lines")).getFirst()).doesNotContainKey("audioUrl");
        assertThat(new RagContentSource(jdbc, properties(false, false)).load(null)).extracting(RagChunk::key)
                .doesNotContain("jpath_grammar:hold", "jpath_grammar:hidden", "jpath_grammar:draft");
        RagChunk opted = new RagContentSource(jdbc, properties(false, true)).load(null).stream()
                .filter(c -> c.key().equals("jpath_grammar:ok")).findFirst().orElseThrow();
        assertThat(opted.text()).contains("例(れい)b").doesNotContain("れい)c", "れい)d");
        assertThat(new RagContentSource(jdbc, properties(false, true)).load(null)).extracting(RagChunk::key)
                .contains("jpath_grammar:hold").doesNotContain("jpath_grammar:hidden", "jpath_grammar:draft");
    }

    @Test
    void confusableRequiresBothVisibleAllowedSides() {
        jgrammar("ga", "REVIEWED", 0);
        jgrammar("gb", "AI_CHECKED", 0);
        jgrammar("gh", "REVIEWED", 1);
        jdbc.update("INSERT INTO jpath_confusable_pair (item_type, code_a, code_b, level, diff_vi, examples_json)"
                + " VALUES ('GRAMMAR','ga','gb','N3','Khác cách dùng',"
                + " '[{\"ja\":\"文\",\"vi\":\"Câu\"}]')");
        jdbc.update("INSERT INTO jpath_confusable_pair (item_type, code_a, code_b, level, diff_vi, examples_json)"
                + " VALUES ('GRAMMAR','ga','gh','N3','Ẩn',NULL)");
        List<RagChunk> chunks = new RagContentSource(jdbc, properties(false, false)).load(null);
        assertThat(chunks).extracting(RagChunk::key).contains("jpath_confusable:GRAMMAR:ga:gb")
                .doesNotContain("jpath_confusable:GRAMMAR:ga:gh");
        assertThat(chunks.stream().filter(c -> c.sourceType().equals("CONFUSABLE")).findFirst().orElseThrow().text())
                .contains("Phân biệt", "Khác cách dùng", "文 — Câu");
    }

    @Test
    void communicationLinesSplitAndPhrasesAppearOnlyInFirstPart() {
        jdbc.update("INSERT INTO cpath_situation VALUES ('TRV-01', 'Mua vé', 'N4')");
        for (int i = 1; i <= 12; i++) jdbc.update("INSERT INTO cpath_line VALUES ('TRV-01', ?, '店員', ?, ?, ?)",
                i, "台詞" + i, "Dòng " + i, i == 1 ? "{台詞|せりふ}1" : null);
        jdbc.update("INSERT INTO cpath_item VALUES ('p1', 'TRV-01', 'PHRASE', 1, '切符をください', 'きっぷをください', 'Cho tôi vé', 'Lịch sự')");
        List<RagChunk> chunks = new RagContentSource(jdbc, properties(false, false)).load(null).stream()
                .filter(c -> c.sourceType().equals("COMM_DIALOGUE")).toList();
        assertThat(chunks).extracting(RagChunk::key).containsExactly("cpath:TRV-01:0", "cpath:TRV-01:1");
        assertThat(chunks.getFirst().text()).contains("Giao tiếp · Mua vé", "店員: 台詞(せりふ)1 — Dòng 1", "Mẫu câu: 切符をください (きっぷをください) — Cho tôi vé. Lịch sự");
        assertThat(chunks.get(1).text()).doesNotContain("Mẫu câu:");
        assertThat(chunks.getFirst().lessonId()).isZero();
        assertThat(chunks.getFirst().textbookId()).isZero();
        assertThat(new RagContentSource(jdbc, properties(false, false)).load(10L)).extracting(RagChunk::sourceType)
                .doesNotContain("JLPT_GRAMMAR", "CONFUSABLE", "COMM_DIALOGUE");
    }

    private void jgrammar(String code, String status, int hidden) {
        jdbc.update("INSERT INTO jpath_grammar (code, level, unit_no, sort_order, priority, pattern, pattern_variants_json,"
                + " family_key, meaning_vi, formation, usage_vi, nuance_vi, common_mistakes_vi, status, report_hidden, authored, updated_at)"
                + " VALUES (?, 'N3', 1, 1, 1, ?, '[\"別形\"]', 'family', 'Nghĩa',"
                + " 'Vる', 'Cách dùng', 'Sắc thái', 'Lỗi', ?, ?, FALSE, CURRENT_TIMESTAMP)", code, "Mẫu " + code, status, hidden != 0);
    }

    private void jexample(String code, String owner, String status, int hidden, int order) {
        jdbc.update("INSERT INTO jpath_example (code, owner_type, owner_code, level, sort_order, role, ja, ja_furigana, vi, status, report_hidden, authored, updated_at)"
                + " VALUES (?, 'GRAMMAR', ?, 'N3', ?, 'MAIN', ?, ?, 'Dịch', ?, ?, FALSE, CURRENT_TIMESTAMP)",
                code, owner, order, "例" + code, "例(れい)" + code, status, hidden != 0);
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

    @Test
    void wordGetsAntonymsAndOnlyAllowedJpathExamples() {
        word(1, "都合", "つごう", "sự tiện");
        jdbc.update("INSERT INTO vocabulary_antonym (vocabulary_id, antonym_text, antonym_reading, source_id) VALUES (1, '不都合', 'ふつごう', 1)");
        jdbc.update("INSERT INTO jpath_vocabulary (code, level, topic_key, sort_order, priority, tier, expression, reading,"
                + " normalized_expression, normalized_reading, kana_only, mnemonic_vi, status, report_hidden, authored, updated_at) VALUES"
                + " ('v1', 'N4', 't', 1, 1, 'CORE', '都合', 'つごう', '都合', 'つごう', FALSE, 'Mẹo', 'AI_CHECKED', FALSE, FALSE, CURRENT_TIMESTAMP)");
        for (String[] e : new String[][]{{"e1", "AI_CHECKED"}, {"e2", "HOLD"}}) {
            jdbc.update("INSERT INTO jpath_example (code, owner_type, owner_code, level, sort_order, role, ja, ja_furigana, vi, status,"
                    + " report_hidden, authored, updated_at) VALUES (?, 'VOCAB', 'v1', 'N4', 1, 'MAIN', '都合', ?, 'Dịch', ?, FALSE, FALSE,"
                    + " CURRENT_TIMESTAMP)", e[0], "都合(つごう)" + e[0], e[1]);
        }

        List<String> extras = new RagLexiconLookup(jdbc, properties(false, false)).lookup("都合 là gì").words().getFirst().extras();

        assertThat(extras).containsExactly("Trái nghĩa: 不都合 (ふつごう)", "Mẹo nhớ: Mẹo", "Ví dụ: 都合(つごう)e1 — Dịch");
    }

    private void word(long id, String expression, String reading, String meaning) {
        jdbc.update("INSERT INTO vocabulary_entry (id, expression, normalized_expression, reading, normalized_reading, meaning, jlpt_level,"
                + " usage_scope, dictionary_status, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, 'N5', 'TEXTBOOK', 'PUBLISHED',"
                + " CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", id, expression, expression, reading, reading, meaning);
    }
}
