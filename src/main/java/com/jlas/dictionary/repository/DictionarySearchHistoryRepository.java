package com.jlas.dictionary.repository;

import com.jlas.dictionary.entity.DictionarySearchHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

/** Repository cho {@link DictionarySearchHistory}. */
@Repository
public interface DictionarySearchHistoryRepository extends JpaRepository<DictionarySearchHistory, Long> {
    List<DictionarySearchHistory> findByUserIdOrderBySearchedAtDesc(Long userId);
}
