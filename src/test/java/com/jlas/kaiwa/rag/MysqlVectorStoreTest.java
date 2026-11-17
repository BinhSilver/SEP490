package com.jlas.kaiwa.rag;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Chỉ mục trong bảng rag_chunk (migration 084): ghi đè cùng id, xoá, tìm cosine, tìm theo tên mẫu, lọc theo bài. */
class MysqlVectorStoreTest {

    private static final String C = "jlas_content_baai_bge_m3";
    private JdbcTemplate jdbc;
    private MysqlVectorStore store;

    @BeforeEach
    void setUp() throws Exception {
        jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:rag-store-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1"));
        String ddl = Files.readString(Path.of("sql/migrations/084_rag_chunk.sql"), StandardCharsets.UTF_8);
        jdbc.execute(ddl.substring(ddl.indexOf("CREATE TABLE")).replaceAll("\\) ENGINE[^;]*;", ");"));
        store = new MysqlVectorStore(jdbc, new ObjectMapper());
    }

    private static RagVectorStore.Point point(String id, long lessonId, String type, String patternKey, float... vector) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source_type", type);
        payload.put("lesson_id", lessonId);
        payload.put("pattern_key", patternKey);
        payload.put("title", id);
        payload.put("text", "đoạn " + id);
        payload.put("citation", Map.of("type", type, "lines", List.of(Map.of("ja", "例"))));
        payload.put("content_hash", "h-" + id);
        return new RagVectorStore.Point(id, vector, payload);
    }

    @Test
    void storesSearchesOverwritesAndDeletes() {
        assertThat(store.dimension(C)).isEmpty();
        store.upsert(C, List.of(
                point("a", 1, "GRAMMAR", "にとって", 1, 0, 0),
                point("b", 1, "READING", "", 0, 1, 0),
                point("c", 0, "JLPT_GRAMMAR", "にとっては", 0.9f, 0.1f, 0)));

        assertThat(store.dimension(C)).contains(3);
        assertThat(store.count(C)).isEqualTo(3);
        assertThat(store.hashes(C, null)).containsOnlyKeys("a", "b", "c");
        assertThat(store.hashes(C, 1L)).containsOnlyKeys("a", "b");

        List<RagVectorStore.Hit> hits = store.search(C, new float[]{2, 0, 0}, 2, 0.5);
        assertThat(hits).extracting(RagVectorStore.Hit::id).containsExactly("a", "c");
        assertThat(hits.getFirst().score()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-6));
        @SuppressWarnings("unchecked")
        Map<String, Object> citation = (Map<String, Object>) hits.getFirst().payload().get("citation");
        assertThat(citation).containsEntry("type", "GRAMMAR");

        // Chỉ đoạn ngữ pháp có tên mẫu chứa đúng chuỗi.
        assertThat(store.byPattern(C, "にとって", 10)).extracting(RagVectorStore.Hit::id).containsExactlyInAnyOrder("a", "c");
        assertThat(store.byPattern(C, "ために", 10)).isEmpty();

        // Ghi đè cùng id thì không tạo bản trùng, và lần tìm sau thấy vector mới ngay.
        store.upsert(C, List.of(point("a", 1, "GRAMMAR", "にとって", 0, 0, 1)));
        assertThat(store.count(C)).isEqualTo(3);
        assertThat(store.search(C, new float[]{0, 0, 1}, 1, 0.5)).extracting(RagVectorStore.Hit::id).containsExactly("a");

        store.delete(C, List.of("a", "b"));
        assertThat(store.hashes(C, null)).containsOnlyKeys("c");
        assertThat(store.search(C, new float[]{0, 0, 1}, 5, 0.5)).isEmpty();
        // Bộ chỉ mục khác (model khác) không lẫn vào.
        assertThat(store.count("jlas_content_other")).isZero();
    }

    @Test
    void deletesInBatchesAndRoundTripsVectors() {
        List<RagVectorStore.Point> points = new ArrayList<>();
        for (int i = 0; i < 1_200; i++) points.add(point("p" + i, i % 3, "DIALOGUE", "", i, 1, 0));
        store.upsert(C, points);
        assertThat(store.count(C)).isEqualTo(1_200);

        assertThat(MysqlVectorStore.fromBytes(MysqlVectorStore.toBytes(new float[]{0.25f, -1.5f, 3f}))).containsExactly(0.25f, -1.5f, 3f);
        store.delete(C, points.stream().map(RagVectorStore.Point::id).toList());
        assertThat(store.count(C)).isZero();
    }
}
