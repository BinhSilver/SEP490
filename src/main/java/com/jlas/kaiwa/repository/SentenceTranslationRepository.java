package com.jlas.kaiwa.repository;

import com.jlas.moderation.entity.SentenceTranslation;
import com.jlas.moderation.entity.SentenceTranslation.SentenceTranslationId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SentenceTranslationRepository
        extends JpaRepository<SentenceTranslation, SentenceTranslationId> {
}
