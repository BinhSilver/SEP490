package com.jlas.dictionary.repository;

import com.jlas.dictionary.entity.DictionarySearchHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/** Repository cho {@link DictionarySearchHistory}. */
@Repository
public interface DictionarySearchHistoryRepository extends JpaRepository<DictionarySearchHistory, Long> {
    List<DictionarySearchHistory> findByUserIdOrderBySearchedAtDesc(Long userId);

    List<DictionarySearchHistory> findTop20ByUser_IdOrderBySearchedAtDesc(Long userId);

    Page<DictionarySearchHistory> findByUserIdOrderBySearchedAtDesc(Long userId, Pageable pageable);

    List<DictionarySearchHistory> findByUser_IdOrderBySearchedAtDescIdDesc(Long userId, Pageable pageable);
}
