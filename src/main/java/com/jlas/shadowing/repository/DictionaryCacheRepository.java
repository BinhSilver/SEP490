package com.jlas.shadowing.repository;

import com.jlas.shadowing.model.DictionaryCache;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Repository;

@Repository
public class DictionaryCacheRepository {
    private final Map<String, DictionaryCache> rows = new ConcurrentHashMap<>();
    public Optional<DictionaryCache> findById(String word) { return Optional.ofNullable(rows.get(word)); }
    public DictionaryCache save(DictionaryCache row) { rows.put(row.getWord(), row); return row; }
}
