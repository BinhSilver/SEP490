package com.jlas.shadowing.repository;

import com.jlas.shadowing.model.FuriganaCache;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.springframework.stereotype.Repository;

@Repository
public class FuriganaCacheRepository {
    private final Map<String, FuriganaCache> rows = new ConcurrentHashMap<>();
    public Optional<FuriganaCache> findByOriginalTextAndTargetLanguage(String text, String lang) {
        try {
            String key = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((text + ":" + lang).getBytes(StandardCharsets.UTF_8)));
            FuriganaCache cached = rows.get(key);
            return cached != null && text.equals(cached.getOriginalText())
                    && lang.equals(cached.getTargetLanguage()) ? Optional.of(cached) : Optional.empty();
        } catch (Exception exception) {
            throw new IllegalStateException("Không đọc được cache furigana", exception);
        }
    }
    public Optional<FuriganaCache> findById(String id) { return Optional.ofNullable(rows.get(id)); }
    public FuriganaCache save(FuriganaCache row) { rows.put(row.getTextHash(), row); return row; }
}
