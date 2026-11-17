package com.jlas.kaiwa.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Reindex đồng bộ theo hash: chạy lại không embed lại, chỉ embed phần đổi, xoá phần không còn trong MySQL. */
class RagIndexServiceTest {

    private final InMemoryStore store = new InMemoryStore();
    private final RagContentSource source = mock(RagContentSource.class);
    private final RagEmbedder embedder = mock(RagEmbedder.class);
    private final AtomicInteger embeddedTexts = new AtomicInteger();
    private List<RagChunk> chunks;

    private RagIndexService service(boolean enabled) {
        RagProperties properties = new RagProperties(enabled, "baai/bge-m3", "jlas_content", 2, 5, 0.45, false, false, 1_000);
        return new RagIndexService(source, embedder, store, properties, new ObjectMapper());
    }

    @BeforeEach
    void setUp() {
        chunks = new ArrayList<>(List.of(
                chunk("grammar_point:1", 1, "N1 は N2 です"),
                chunk("grammar_point:2", 1, "N1 も N2 です"),
                chunk("section:7:DIALOGUE:0", 2, "たなか: これは なんですか。")));
        when(embedder.model()).thenReturn("bge-m3");
        when(embedder.embed(anyList())).thenAnswer(call -> {
            List<String> texts = call.getArgument(0);
            embeddedTexts.addAndGet(texts.size());
            return texts.stream().map(t -> new float[]{t.length(), 1f, 0.5f}).toList();
        });
        when(source.load(isNull())).thenAnswer(call -> List.copyOf(chunks));
        when(source.load(any(Long.class))).thenAnswer(call -> chunks.stream().filter(c -> c.lessonId() == (Long) call.getArgument(0)).toList());
    }

    private static RagChunk chunk(String key, long lessonId, String text) {
        return new RagChunk(key, key.startsWith("grammar") ? "GRAMMAR" : "DIALOGUE", 1, lessonId, 1, "N5", text, text,
                Map.of("lesson", "Bài " + lessonId));
    }

    @Test
    void secondRunWithUnchangedContentEmbedsNothing() {
        RagIndexService service = service(true);

        RagIndexService.Report first = service.reindex(null);
        int afterFirst = embeddedTexts.get();
        RagIndexService.Report second = service.reindex(null);

        assertThat(first.embedded()).isEqualTo(3);
        assertThat(second.embedded()).isZero();
        assertThat(second.unchanged()).isEqualTo(3);
        assertThat(second.deleted()).isZero();
        assertThat(embeddedTexts.get()).isEqualTo(afterFirst);
        assertThat(store.points).hasSize(3);
        assertThat(store.dimension).isEqualTo(3);
    }

    @Test
    void pointIdsAreDerivedFromMySqlRecordsNotRunOrder() {
        RagIndexService service = service(true);
        service.reindex(null);
        var idsBefore = new HashMap<>(store.points).keySet();

        java.util.Collections.reverse(chunks);
        service.reindex(null);

        assertThat(store.points.keySet()).isEqualTo(idsBefore);
        assertThat(chunks.getFirst().pointId()).isEqualTo(chunk("section:7:DIALOGUE:0", 2, "x").pointId());
    }

    @Test
    void onlyChangedChunkIsReEmbeddedAndRemovedChunkIsDeleted() {
        RagIndexService service = service(true);
        service.reindex(null);

        chunks.set(0, chunk("grammar_point:1", 1, "N1 は N2 です (đã sửa)"));
        chunks.remove(1);
        RagIndexService.Report report = service.reindex(null);

        assertThat(report.embedded()).isEqualTo(1);
        assertThat(report.unchanged()).isEqualTo(1);
        assertThat(report.deleted()).isEqualTo(1);
        assertThat(store.points).hasSize(2);
        assertThat(store.points.get(chunks.getFirst().pointId()).payload().get("text")).isEqualTo("N1 は N2 です (đã sửa)");
    }

    @Test
    void lessonScopedRunLeavesOtherLessonsAlone() {
        RagIndexService service = service(true);
        service.reindex(null);

        chunks.removeIf(c -> c.lessonId() == 1);
        RagIndexService.Report report = service.reindex(1L);

        assertThat(report.deleted()).isEqualTo(2);
        assertThat(store.points).hasSize(1);
        assertThat(store.points.values().iterator().next().payload().get("lesson_id")).isEqualTo(2L);
    }

    @Test
    void lessonScopedRunPreservesGlobalChunksAndFullRunRemovesStaleOnes() {
        RagChunk global = new RagChunk("jpath_grammar:g1", "JLPT_GRAMMAR", 0, 0, 0, "N3", "Mẫu", "Mẫu",
                Map.of("type", "JLPT_GRAMMAR"));
        chunks.add(global);
        RagIndexService service = service(true);
        service.reindex(null);

        chunks.remove(global);
        assertThat(service.reindex(1L).deleted()).isZero();
        assertThat(store.points).containsKey(global.pointId());
        assertThat(service.reindex(null).deleted()).isEqualTo(1);
        assertThat(store.points).doesNotContainKey(global.pointId());
    }

    @Test
    void disabledRagRefusesToRun() {
        assertThatThrownBy(() -> service(false).reindex(null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
        assertThat(store.points).isEmpty();
    }

    @Test
    void concurrentReindexIsRejected() throws Exception {
        RagIndexService service = service(true);
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(source.load(isNull())).thenAnswer(call -> {
            inside.countDown();
            release.await(5, TimeUnit.SECONDS);
            return List.copyOf(chunks);
        });
        Thread first = new Thread(() -> service.reindex(null));
        first.start();
        assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> service.reindex(null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONFLICT));
        release.countDown();
        first.join(5_000);
        assertThat(store.points).hasSize(3);
    }

    /** Kho vector trong bộ nhớ, đủ cho các thao tác index dùng. */
    static final class InMemoryStore implements RagVectorStore {
        final Map<String, Point> points = new HashMap<>();
        Integer dimension;

        @Override public Optional<Integer> dimension(String collection) { return Optional.ofNullable(dimension); }
        @Override public void createCollection(String collection, int dimension) { this.dimension = dimension; }
        @Override public Map<String, String> hashes(String collection, Long lessonId) {
            Map<String, String> result = new HashMap<>();
            points.forEach((id, p) -> {
                if (lessonId == null || lessonId.equals(p.payload().get("lesson_id"))) result.put(id, (String) p.payload().get("content_hash"));
            });
            return result;
        }
        @Override public void upsert(String collection, List<Point> batch) {
            batch.forEach(p -> {
                assertThat(p.vector()).hasSize(dimension);
                points.put(p.id(), p);
            });
        }
        @Override public void delete(String collection, Collection<String> ids) { ids.forEach(points::remove); }
        @Override public List<Hit> search(String collection, float[] vector, int limit, double minScore) { return List.of(); }
        @Override public long count(String collection) { return points.size(); }
    }
}
