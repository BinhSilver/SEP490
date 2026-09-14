package com.jlas.shadowing.repository;

import com.jlas.shadowing.model.FuriganaCache;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Repository;

@Repository
public class FuriganaCacheRepository {
    private final Map<String, FuriganaCache> rows = new ConcurrentHashMap<>();
    public Optional<FuriganaCache> findByOriginalTextAndTargetLanguage(String text, String lang) { return rows.values().stream().filter(x -> text.equals(x.getOriginalText()) && lang.equals(x.getTargetLanguage())).findFirst(); }
    public Optional<FuriganaCache> findById(String id) { return Optional.ofNullable(rows.get(id)); }
    public FuriganaCache save(FuriganaCache row) { rows.put(row.getTextHash(), row); return row; }
}
