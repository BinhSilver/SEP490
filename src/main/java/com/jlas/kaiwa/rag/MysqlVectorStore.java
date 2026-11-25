package com.jlas.kaiwa.rag;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Kho vector của RAG trong bảng {@code rag_chunk} (migration 084). Cả bộ chỉ mục được nạp vào bộ nhớ (vector đã chuẩn
 * hóa) và tìm bằng cosine; vài nghìn đoạn mất dưới 10 ms. Bản nạp được kiểm lại (số dòng, lần sửa cuối) tối đa mỗi
 * {@value #CHECK_INTERVAL_MS} ms, nên một máy chủ khác reindex thì máy này cũng thấy sau vài chục giây.
 * ponytail: duyệt toàn bộ mỗi lần tìm; khi vượt cỡ 100.000 đoạn thì cần chỉ mục vector thật (Qdrant, pgvector...).
 */
@Repository
public class MysqlVectorStore implements RagVectorStore {

    static final long CHECK_INTERVAL_MS = 30_000;
    private static final Set<String> GRAMMAR_TYPES = Set.of("GRAMMAR", "JLPT_GRAMMAR", "CONFUSABLE");
    private static final int DELETE_BATCH = 500;

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Map<String, Snapshot> cache = new ConcurrentHashMap<>();

    public MysqlVectorStore(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    private record Entry(String id, float[] unit, String sourceType, String patternKey, Map<String, Object> payload) { }

    private record Snapshot(List<Entry> entries, long rows, Timestamp lastUpdate, long checkedAt) { }

    @Override
    public Optional<Integer> dimension(String collection) {
        List<Integer> dims = jdbc.queryForList("SELECT dims FROM rag_chunk WHERE collection = ? LIMIT 1", Integer.class, collection);
        return dims.stream().findFirst();
    }

    /** Không cần tạo trước: bộ chỉ mục có khi có dòng đầu tiên. */
    @Override
    public void createCollection(String collection, int dimension) {
    }

    @Override
    public Map<String, String> hashes(String collection, Long lessonId) {
        Map<String, String> out = new HashMap<>();
        String sql = "SELECT id, content_hash FROM rag_chunk WHERE collection = ?" + (lessonId == null ? "" : " AND lesson_id = ?");
        Object[] args = lessonId == null ? new Object[]{collection} : new Object[]{collection, lessonId};
        jdbc.query(sql, rs -> { out.put(rs.getString("id"), rs.getString("content_hash")); }, args);
        return out;
    }

    @Override
    public void upsert(String collection, List<Point> points) {
        if (points.isEmpty()) return;
        List<Object[]> rows = new ArrayList<>(points.size());
        for (Point p : points) {
            Map<String, Object> payload = p.payload();
            rows.add(new Object[]{p.id(), collection, number(payload.get("lesson_id")), String.valueOf(payload.get("source_type")),
                    Objects.toString(payload.get("pattern_key"), ""), String.valueOf(payload.get("content_hash")), p.vector().length,
                    toBytes(p.vector()), write(payload)});
        }
        jdbc.batchUpdate("INSERT INTO rag_chunk (id, collection, lesson_id, source_type, pattern_key, content_hash, dims, vector, payload)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE collection = VALUES(collection),"
                + " lesson_id = VALUES(lesson_id), source_type = VALUES(source_type), pattern_key = VALUES(pattern_key),"
                + " content_hash = VALUES(content_hash), dims = VALUES(dims), vector = VALUES(vector), payload = VALUES(payload)", rows);
        cache.remove(collection);
    }

    @Override
    public void delete(String collection, Collection<String> ids) {
        if (ids.isEmpty()) return;
        List<String> all = List.copyOf(ids);
        for (int from = 0; from < all.size(); from += DELETE_BATCH) {
            List<String> part = all.subList(from, Math.min(all.size(), from + DELETE_BATCH));
            String marks = String.join(",", java.util.Collections.nCopies(part.size(), "?"));
            List<Object> args = new ArrayList<>(part);
            args.add(0, collection);
            jdbc.update("DELETE FROM rag_chunk WHERE collection = ? AND id IN (" + marks + ")", args.toArray());
        }
        cache.remove(collection);
    }

    @Override
    public List<Hit> search(String collection, float[] vector, int limit, double minScore) {
        float[] query = unit(vector);
        List<Hit> hits = new ArrayList<>();
        for (Entry e : snapshot(collection).entries()) {
            if (e.unit().length != query.length) continue;
            double score = 0;
            for (int i = 0; i < query.length; i++) score += query[i] * e.unit()[i];
            if (score >= minScore) hits.add(new Hit(e.id(), score, e.payload()));
        }
        hits.sort(Comparator.comparingDouble(Hit::score).reversed());
        return hits.size() > limit ? List.copyOf(hits.subList(0, limit)) : hits;
    }

    @Override
    public List<Hit> byPattern(String collection, String fragment, int limit) {
        List<Hit> hits = new ArrayList<>();
        for (Entry e : snapshot(collection).entries()) {
            if (hits.size() >= limit) break;
            if (GRAMMAR_TYPES.contains(e.sourceType()) && e.patternKey().contains(fragment)) hits.add(new Hit(e.id(), 1.0, e.payload()));
        }
        return hits;
    }

    @Override
    public long count(String collection) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM rag_chunk WHERE collection = ?", Long.class, collection);
        return n == null ? 0 : n;
    }

    /** Bản nạp trong bộ nhớ; nạp lại khi số dòng hoặc lần sửa cuối trong DB đã đổi. */
    private Snapshot snapshot(String collection) {
        long now = System.currentTimeMillis();
        Snapshot current = cache.get(collection);
        if (current != null && now - current.checkedAt() < CHECK_INTERVAL_MS) return current;
        synchronized (this) {
            current = cache.get(collection);
            if (current != null && now - current.checkedAt() < CHECK_INTERVAL_MS) return current;
            Map<String, Object> state = jdbc.queryForMap("SELECT COUNT(*) AS n, MAX(updated_at) AS last FROM rag_chunk WHERE collection = ?",
                    collection);
            long rows = ((Number) state.get("n")).longValue();
            Timestamp last = state.get("last") instanceof Timestamp t ? t
                    : state.get("last") instanceof java.time.LocalDateTime d ? Timestamp.valueOf(d) : null;
            if (current != null && current.rows() == rows && Objects.equals(current.lastUpdate(), last)) {
                current = new Snapshot(current.entries(), rows, last, now);
            } else {
                List<Entry> entries = jdbc.query("SELECT id, source_type, pattern_key, vector, payload FROM rag_chunk WHERE collection = ?",
                        (rs, n) -> new Entry(rs.getString("id"), unit(fromBytes(rs.getBytes("vector"))), rs.getString("source_type"),
                                rs.getString("pattern_key"), read(rs.getString("payload"))), collection);
                current = new Snapshot(List.copyOf(entries), rows, last, now);
            }
            cache.put(collection, current);
            return current;
        }
    }

    static byte[] toBytes(float[] vector) {
        ByteBuffer buffer = ByteBuffer.allocate(vector.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (float v : vector) buffer.putFloat(v);
        return buffer.array();
    }

    static float[] fromBytes(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[] out = new float[bytes.length / Float.BYTES];
        for (int i = 0; i < out.length; i++) out[i] = buffer.getFloat();
        return out;
    }

    private static float[] unit(float[] v) {
        double norm = 0;
        for (float x : v) norm += x * x;
        norm = Math.sqrt(norm);
        float[] out = new float[v.length];
        if (norm == 0) return out;
        for (int i = 0; i < v.length; i++) out[i] = (float) (v[i] / norm);
        return out;
    }

    private static long number(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }

    private String write(Map<String, Object> payload) {
        try {
            return json.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Map<String, Object> read(String payload) {
        try {
            return json.readValue(payload, new TypeReference<LinkedHashMap<String, Object>>() { });
        } catch (Exception e) {
            throw new IllegalStateException("Invalid rag_chunk payload", e);
        }
    }
}
