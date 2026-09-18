package com.jlas.dictionary.repository;

import com.jlas.dictionary.entity.VocabularyEntry;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

/**
 * Repository cho {@link VocabularyEntry}.
 *
 * <p>Các cột tiếng Nhật (normalized_expression, normalized_reading) và toàn bộ schema JLAS2-19
 * đều dùng collation {@code utf8mb4_unicode_ci} (mặc định toàn dự án). Collation này coi các cặp
 * thanh/trọc/bán trọc âm (は/ば/ぱ, か/が, た/だ...) là tương đương khi so sánh chuỗi, nên so khớp
 * mặc định có thể lẫn "はは" với "ばば". Các query tra cứu chính xác/prefix theo Kanji/Kana dưới
 * đây ép {@code COLLATE utf8mb4_bin} ngay tại câu query (không đổi collation cột/schema) để so
 * khớp đúng byte, còn romaji/meaning (ASCII/Latin) không bị ảnh hưởng nên giữ derived query.</p>
 */
@Repository
public interface VocabularyEntryRepository extends JpaRepository<VocabularyEntry, Long> {
    List<VocabularyEntry> findByReadingContainingIgnoreCase(String keyword);

    List<VocabularyEntry> findByIdIn(List<Long> ids);

    @Query(value = "select * from vocabulary_entry "
            + "where normalized_expression collate utf8mb4_bin = :expression "
            + "and dictionary_status = :status limit 1", nativeQuery = true)
    Optional<VocabularyEntry> findFirstByNormalizedExpressionAndDictionaryStatus(
            @Param("expression") String normalizedExpression, @Param("status") String dictionaryStatus);

    @Query(value = "select * from vocabulary_entry "
            + "where normalized_expression collate utf8mb4_bin like concat(:prefix, '%') "
            + "and dictionary_status = :status order by id asc", nativeQuery = true)
    List<VocabularyEntry> findByNormalizedExpressionStartingWithAndDictionaryStatusOrderByIdAsc(
            @Param("prefix") String normalizedExpressionPrefix, @Param("status") String dictionaryStatus,
            Pageable pageable);

    @Query(value = "select * from vocabulary_entry "
            + "where normalized_expression collate utf8mb4_bin like concat('%', :infix, '%') "
            + "and dictionary_status = :status order by id asc", nativeQuery = true)
    List<VocabularyEntry> findByNormalizedExpressionContainingAndDictionaryStatusOrderByIdAsc(
            @Param("infix") String normalizedExpressionInfix, @Param("status") String dictionaryStatus,
            Pageable pageable);

    @Query(value = "select * from vocabulary_entry "
            + "where normalized_reading collate utf8mb4_bin = :reading "
            + "and dictionary_status = :status limit 1", nativeQuery = true)
    Optional<VocabularyEntry> findFirstByNormalizedReadingAndDictionaryStatus(
            @Param("reading") String normalizedReading, @Param("status") String dictionaryStatus);

    @Query(value = "select * from vocabulary_entry "
            + "where normalized_reading collate utf8mb4_bin like concat(:prefix, '%') "
            + "and dictionary_status = :status order by id asc", nativeQuery = true)
    List<VocabularyEntry> findByNormalizedReadingStartingWithAndDictionaryStatusOrderByIdAsc(
            @Param("prefix") String normalizedReadingPrefix, @Param("status") String dictionaryStatus,
            Pageable pageable);

    Optional<VocabularyEntry> findFirstByReadingRomajiIgnoreCaseAndDictionaryStatus(
            String readingRomaji, String dictionaryStatus);

    List<VocabularyEntry> findByReadingRomajiStartingWithIgnoreCaseAndDictionaryStatusOrderByIdAsc(
            String readingRomajiPrefix, String dictionaryStatus, Pageable pageable);

    List<VocabularyEntry> findByMeaningContainingAndDictionaryStatusOrderByIdAsc(
            String meaningInfix, String dictionaryStatus, Pageable pageable);
}
