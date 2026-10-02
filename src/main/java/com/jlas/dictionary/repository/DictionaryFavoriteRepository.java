package com.jlas.dictionary.repository;

import com.jlas.dictionary.entity.DictionaryFavorite;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DictionaryFavoriteRepository extends JpaRepository<DictionaryFavorite, DictionaryFavorite.DictionaryFavoriteId> {
    Page<DictionaryFavorite> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);
}
