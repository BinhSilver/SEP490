package com.jlas.kaiwa.rag;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.client.RestTemplate;

/**
 * Chạy với Qdrant thật khi có biến {@code JLAS_TEST_QDRANT_URL} (vd. http://127.0.0.1:6333); không có thì bỏ qua.
 * Kiểm tra đúng các lệnh REST mà index dùng, gồm phân trang scroll khi có hơn 256 điểm.
 */
@EnabledIfEnvironmentVariable(named = "JLAS_TEST_QDRANT_URL", matches = "http://(127\\.0\\.0\\.1|localhost):\\d+")
class QdrantVectorStoreTest {

    private final String url = System.getenv("JLAS_TEST_QDRANT_URL");
    private final String collection = "jlas_test_" + UUID.randomUUID().toString().replace("-", "");
    private final QdrantVectorStore store = new QdrantVectorStore(
            new RagProperties(true, url, "", "jlas_test", 16, 5, 0.45, false, false, 10_000), new ObjectMapper());

    @AfterEach
    void dropCollection() {
        new RestTemplate().delete(url + "/collections/" + collection);
    }

    private static RagVectorStore.Point point(int i, long lessonId) {
        float[] vector = {(float) Math.cos(i), (float) Math.sin(i), 0.1f};
        String id = UUID.nameUUIDFromBytes(("p" + i).getBytes()).toString();
        return new RagVectorStore.Point(id, vector, Map.of("lesson_id", lessonId, "content_hash", "h" + i, "source_type", "GRAMMAR",
                "text", "đoạn " + i, "citation", Map.of("lesson", "Bài " + lessonId)));
    }

    @Test
    void roundTripsPointsHashesSearchAndDelete() {
        assertThat(store.dimension(collection)).isEmpty();
        store.createCollection(collection, 3);
        assertThat(store.dimension(collection)).contains(3);

        List<RagVectorStore.Point> points = new ArrayList<>();
        for (int i = 0; i < 300; i++) points.add(point(i, i < 10 ? 1L : 2L));
        store.upsert(collection, points.subList(0, 150));
        store.upsert(collection, points.subList(150, 300));

        assertThat(store.count(collection)).isEqualTo(300);
        assertThat(store.hashes(collection, null)).hasSize(300).containsEntry(points.get(42).id(), "h42");
        assertThat(store.hashes(collection, 1L)).hasSize(10);

        List<RagVectorStore.Hit> hits = store.search(collection, points.get(7).vector(), 3, 0.5);
        assertThat(hits).isNotEmpty();
        assertThat(hits.getFirst().id()).isEqualTo(points.get(7).id());
        assertThat(hits.getFirst().payload()).containsEntry("text", "đoạn 7");
        assertThat(hits.getFirst().payload().get("citation")).isEqualTo(Map.of("lesson", "Bài 1"));

        // Upsert cùng id thì ghi đè, không nhân bản.
        store.upsert(collection, List.of(point(7, 1L)));
        assertThat(store.count(collection)).isEqualTo(300);

        store.delete(collection, store.hashes(collection, 1L).keySet());
        assertThat(store.count(collection)).isEqualTo(290);
        assertThat(store.hashes(collection, 1L)).isEmpty();
    }
}
