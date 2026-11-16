package com.jlas.kaiwa.rag;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Kho vector của RAG. Bản thật là Qdrant; test dùng bản trong bộ nhớ. */
public interface RagVectorStore {

    /** Số chiều vector của collection, rỗng nếu collection chưa có. */
    Optional<Integer> dimension(String collection);

    void createCollection(String collection, int dimension);

    /** id điểm → content_hash; {@code lessonId} khác null thì chỉ lấy điểm của bài đó. */
    Map<String, String> hashes(String collection, Long lessonId);

    void upsert(String collection, List<Point> points);

    void delete(String collection, Collection<String> ids);

    List<Hit> search(String collection, float[] vector, int limit, double minScore);

    long count(String collection);

    record Point(String id, float[] vector, Map<String, Object> payload) { }

    record Hit(String id, double score, Map<String, Object> payload) { }
}
