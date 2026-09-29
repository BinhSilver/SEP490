package com.jlas.kaiwa.repository;

import com.jlas.kaiwa.model.*;
import com.jlas.moderation.entity.SentenceTranslation;
import com.jlas.moderation.entity.SentenceTranslation.SentenceTranslationId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Compatibility facade: translations are durable in sentence_translation;
 * generated furigana remains runtime-only as required by the merged schema.
 */
@Repository
@RequiredArgsConstructor
public class TextAnnotationCacheRepository {
    private static final String DEFAULT_LANGUAGE = "vi";
    private final SentenceTranslationRepository translationRepository;
    private final Map<String, String> runtimeFurigana = new ConcurrentHashMap<>();

    public Optional<TextAnnotationCache> findByContentHash(String hash) {
        Optional<SentenceTranslation> persisted = translationRepository
                .findById(new SentenceTranslationId(hash, DEFAULT_LANGUAGE));
        if (persisted.isEmpty() && !runtimeFurigana.containsKey(hash)) return Optional.empty();
        TextAnnotationCache row = new TextAnnotationCache();
        row.setContentHash(hash);
        row.setFuriganaJson(runtimeFurigana.get(hash));
        persisted.ifPresent(value -> {
            row.setContentJa(value.getSourceText());
            row.setTranslationVi(value.getTranslatedText());
        });
        return Optional.of(row);
    }

    public TextAnnotationCache save(TextAnnotationCache row) {
        if (row.getFuriganaJson() != null) runtimeFurigana.put(row.getContentHash(), row.getFuriganaJson());
        if (row.getTranslationVi() != null && row.getContentJa() != null) {
            SentenceTranslationId id = new SentenceTranslationId(row.getContentHash(), DEFAULT_LANGUAGE);
            SentenceTranslation entity = translationRepository.findById(id)
                    .orElseGet(() -> SentenceTranslation.builder().id(id).build());
            entity.setSourceText(row.getContentJa());
            entity.setTranslatedText(row.getTranslationVi());
            translationRepository.save(entity);
        }
        return row;
    }
}
