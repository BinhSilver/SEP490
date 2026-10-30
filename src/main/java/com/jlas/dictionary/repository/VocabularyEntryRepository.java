package com.jlas.dictionary.repository;

import com.jlas.dictionary.entity.VocabularyEntry;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Repository cho {@link VocabularyEntry}.
 *
 * <p>Collation mặc định {@code utf8mb4_unicode_ci} coi các cặp thanh/trọc/bán trọc âm (は/ば/ぱ,
 * か/が...) là tương đương, nên có thể lẫn "はは" với "ばば". Từ migration 005, cột
 * normalized_expression/normalized_reading dùng {@code utf8mb4_bin} nên các query dưới đây so sánh
 * trực tiếp. Không thêm {@code COLLATE} tường minh: dù trùng collation cột, MySQL khi đó bỏ index
 * uk_vocabulary_normalized và quét theo khoá chính (đo được ~340 ms thay vì ~3 ms cho prefix).
 * Romaji/nghĩa (Latin) giữ collation không phân biệt hoa/thường.</p>
 */
@Repository
public interface VocabularyEntryRepository extends JpaRepository<VocabularyEntry, Long> {
    List<VocabularyEntry> findByReadingContainingIgnoreCase(String keyword);

    List<VocabularyEntry> findByIdIn(List<Long> ids);

    @Query(value = "select * from vocabulary_entry "
            + "where normalized_expression = :expression "
            + "and dictionary_status = :status order by id asc limit 1", nativeQuery = true)
    Optional<VocabularyEntry> findFirstByNormalizedExpressionAndDictionaryStatus(
            @Param("expression") String normalizedExpression, @Param("status") String dictionaryStatus);

    @Query(value = "select * from vocabulary_entry "
            + "where normalized_expression in (:expressions) "
            + "and dictionary_status = :status order by id asc", nativeQuery = true)
    List<VocabularyEntry> findByNormalizedExpressionInAndDictionaryStatusOrderByIdAsc(
            @Param("expressions") Collection<String> normalizedExpressions, @Param("status") String dictionaryStatus);

    @Query(value = "select * from vocabulary_entry "
            + "where normalized_expression like concat(:prefix, '%') "
            + "and dictionary_status = :status order by id asc", nativeQuery = true)
    List<VocabularyEntry> findByNormalizedExpressionStartingWithAndDictionaryStatusOrderByIdAsc(
            @Param("prefix") String normalizedExpressionPrefix, @Param("status") String dictionaryStatus,
            Pageable pageable);

    @Query(value = "select * from vocabulary_entry "
            + "where normalized_expression like concat('%', :infix, '%') "
            + "and dictionary_status = :status order by id asc", nativeQuery = true)
    List<VocabularyEntry> findByNormalizedExpressionContainingAndDictionaryStatusOrderByIdAsc(
            @Param("infix") String normalizedExpressionInfix, @Param("status") String dictionaryStatus,
            Pageable pageable);

    @Query(value = "select * from vocabulary_entry "
            + "where normalized_reading = :reading "
            + "and dictionary_status = :status order by id asc limit 1", nativeQuery = true)
    Optional<VocabularyEntry> findFirstByNormalizedReadingAndDictionaryStatus(
            @Param("reading") String normalizedReading, @Param("status") String dictionaryStatus);

    @Query(value = "select * from vocabulary_entry "
            + "where normalized_reading like concat(:prefix, '%') "
            + "and dictionary_status = :status order by id asc", nativeQuery = true)
    List<VocabularyEntry> findByNormalizedReadingStartingWithAndDictionaryStatusOrderByIdAsc(
            @Param("prefix") String normalizedReadingPrefix, @Param("status") String dictionaryStatus,
            Pageable pageable);

    // reading_romaji dùng collation unicode_ci (vốn không phân biệt hoa/thường) nên so sánh trực
    // tiếp để MySQL dùng index idx_vocabulary_romaji; derived query "IgnoreCase" sinh upper(...)
    // làm mất index và quét toàn bảng.
    @Query(value = "select * from vocabulary_entry where reading_romaji = :romaji "
            + "and dictionary_status = :status order by id asc limit 1", nativeQuery = true)
    Optional<VocabularyEntry> findFirstByReadingRomajiIgnoreCaseAndDictionaryStatus(
            @Param("romaji") String readingRomaji, @Param("status") String dictionaryStatus);

    @Query(value = "select * from vocabulary_entry where reading_romaji like concat(:prefix, '%') "
            + "and dictionary_status = :status order by id asc", nativeQuery = true)
    List<VocabularyEntry> findByReadingRomajiStartingWithIgnoreCaseAndDictionaryStatusOrderByIdAsc(
            @Param("prefix") String readingRomajiPrefix, @Param("status") String dictionaryStatus,
            Pageable pageable);

    /**
     * Âm Hán Việt của cả từ, so khớp đúng dấu tiếng Việt nhưng không phân biệt hoa/thường
     * ({@code utf8mb4_0900_as_ci}); điều kiện {@code han_viet = :value} theo collation cột
     * (unicode_ci, bỏ dấu) chỉ để dùng index idx_vocabulary_han_viet rồi lọc lại đúng dấu.
     */
    @Query(value = "select * from vocabulary_entry "
            + "where han_viet = :value and han_viet collate utf8mb4_0900_as_ci = :value "
            + "and dictionary_status = 'PUBLISHED' order by id asc", nativeQuery = true)
    List<VocabularyEntry> findByHanViet(@Param("value") String hanViet, Pageable pageable);

    /** Từ loại của các mục có normalized_expression nằm trong {@code terms} (kiểm tra thể từ điển). */
    @Query(value = "select e.id as vocabularyId, e.normalized_expression as term, p.code as partOfSpeech "
            + "from vocabulary_entry e join vocabulary_sense s on s.vocabulary_id = e.id "
            + "join sense_part_of_speech sp on sp.sense_id = s.id "
            + "join dictionary_part_of_speech p on p.id = sp.part_of_speech_id "
            + "where e.normalized_expression in (:terms) and e.dictionary_status = 'PUBLISHED' "
            + "order by e.id asc", nativeQuery = true)
    List<PartOfSpeechRow> findPartsOfSpeechByExpressionIn(@Param("terms") Collection<String> terms);

    /** Như {@link #findPartsOfSpeechByExpressionIn} nhưng so với normalized_reading (query kana). */
    @Query(value = "select e.id as vocabularyId, e.normalized_reading as term, p.code as partOfSpeech "
            + "from vocabulary_entry e join vocabulary_sense s on s.vocabulary_id = e.id "
            + "join sense_part_of_speech sp on sp.sense_id = s.id "
            + "join dictionary_part_of_speech p on p.id = sp.part_of_speech_id "
            + "where e.normalized_reading in (:terms) and e.dictionary_status = 'PUBLISHED' "
            + "order by e.id asc", nativeQuery = true)
    List<PartOfSpeechRow> findPartsOfSpeechByReadingIn(@Param("terms") Collection<String> terms);

    interface PartOfSpeechRow {
        Long getVocabularyId();
        String getTerm();
        String getPartOfSpeech();
    }
}
