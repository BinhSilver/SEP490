package com.jlas.dictionary.repository;

import com.jlas.dictionary.entity.VocabularyEntry;
import org.springframework.data.domain.Page;
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
 * か/が...) là tương đương, nên có thể lẫn "はは" với "ばば". Từ migration 059, cột
 * normalized_expression/normalized_reading dùng {@code utf8mb4_0900_as_cs} (accent/case-sensitive),
 * phân biệt dakuten. Các query dưới đây so sánh trực tiếp, không ép {@code COLLATE}, để dùng
 * index uk_vocabulary_normalized.
 * Romaji/nghĩa (Latin) giữ collation không phân biệt hoa/thường.</p>
 *
 * <p>Editor search uses the default collation of expression/reading, so a missing dakuten can
 * still return a useful match; duplicate checks use the accent- and case-sensitive normalized columns.</p>
 */
@Repository
public interface VocabularyEntryRepository extends JpaRepository<VocabularyEntry, Long> {
    String VIETNAMESE_MEANING_LETTERS = "àáạảãâầấậẩẫăằắặẳẵèéẹẻẽêềếệểễìíịỉĩòóọỏõôồốộổỗơờớợởỡùúụủũưừứựửữỳýỵỷỹđ";

    @Query(value = "select * from vocabulary_entry where jlpt_level = :level "
            + "and dictionary_status = 'PUBLISHED' and usage_scope <> 'TEXTBOOK' "
            + "and meaning is not null and regexp_like(meaning collate utf8mb4_0900_as_ci, '["
            + VIETNAMESE_MEANING_LETTERS + "]', 'i') "
            + "order by rand(:seed) limit 5", nativeQuery = true)
    List<VocabularyEntry> findDailyWordsForLevel(@Param("level") String level, @Param("seed") long seed);

    @Query(value = "select * from vocabulary_entry where normalized_reading in (:readings) "
            + "and dictionary_status = 'PUBLISHED' and usage_scope <> 'TEXTBOOK' order by id asc", nativeQuery = true)
    List<VocabularyEntry> findLookupByNormalizedReadingInAndDictionaryStatusOrderByIdAsc(
            @Param("readings") Collection<String> readings);

    List<VocabularyEntry> findByNormalizedExpressionInAndDictionaryStatus(Collection<String> expressions, String status);

    List<VocabularyEntry> findByReadingContainingIgnoreCase(String keyword);

    List<VocabularyEntry> findByIdIn(List<Long> ids);

    /**
     * Kiểm trùng trước khi tạo từ mới. Cố ý so sánh bằng collation của chính cột (KHÔNG ép COLLATE tường minh)
     * để kết quả trùng khớp đúng với cách uk_vocabulary_normalized đánh giá hai giá trị là bằng nhau.
     */
    @Query(value = "select * from vocabulary_entry "
            + "where normalized_expression = :expression and normalized_reading = :reading limit 1",
            nativeQuery = true)
    Optional<VocabularyEntry> findByNormalizedPair(@Param("expression") String normalizedExpression,
                                                   @Param("reading") String normalizedReading);

    /**
     * Cặp (normalized_expression, normalized_reading) của các cách viết cho trước — một query cho cả bài, dùng để
     * xác định chữ Hán nào trong đề "cách đọc chữ Hán" có cách đọc đúng bằng đáp án (gạch chân đúng chữ).
     */
    @Query(value = "select normalized_expression, normalized_reading from vocabulary_entry "
            + "where normalized_expression in (:expressions)", nativeQuery = true)
    List<Object[]> findReadingsByNormalizedExpressions(@Param("expressions") Collection<String> normalizedExpressions);

    @Query(value = "select * from vocabulary_entry "
            + "where normalized_expression = :expression "
            + "and dictionary_status = :status and usage_scope <> 'TEXTBOOK' order by id asc limit 1", nativeQuery = true)
    Optional<VocabularyEntry> findFirstByNormalizedExpressionAndDictionaryStatus(
            @Param("expression") String normalizedExpression, @Param("status") String dictionaryStatus);

    @Query(value = "select * from vocabulary_entry "
            + "where normalized_expression in (:expressions) "
            + "and dictionary_status = :status order by id asc", nativeQuery = true)
    List<VocabularyEntry> findByNormalizedExpressionInAndDictionaryStatusOrderByIdAsc(
            @Param("expressions") Collection<String> normalizedExpressions, @Param("status") String dictionaryStatus);

    /** Segmented public lookup; the unfiltered variant above also resolves word-detail antonyms. */
    @Query(value = "select * from vocabulary_entry "
            + "where normalized_expression in (:expressions) "
            + "and dictionary_status = :status and usage_scope <> 'TEXTBOOK' order by id asc", nativeQuery = true)
    List<VocabularyEntry> findLookupByNormalizedExpressionInAndDictionaryStatusOrderByIdAsc(
            @Param("expressions") Collection<String> normalizedExpressions, @Param("status") String dictionaryStatus);

    @Query(value = "select * from vocabulary_entry "
            + "where normalized_expression = :expression "
            + "and dictionary_status = :status order by id asc", nativeQuery = true)
    List<VocabularyEntry> findByNormalizedExpressionAndDictionaryStatusOrderByIdAsc(
            @Param("expression") String normalizedExpression, @Param("status") String dictionaryStatus);

    @Query(value = "select * from vocabulary_entry "
            + "where normalized_expression like concat(:prefix, '%') "
            + "and dictionary_status = :status and usage_scope <> 'TEXTBOOK' order by id asc", nativeQuery = true)
    List<VocabularyEntry> findByNormalizedExpressionStartingWithAndDictionaryStatusOrderByIdAsc(
            @Param("prefix") String normalizedExpressionPrefix, @Param("status") String dictionaryStatus,
            Pageable pageable);

    @Query(value = "select * from vocabulary_entry "
            + "where normalized_expression like concat('%', :infix, '%') "
            + "and dictionary_status = :status and usage_scope <> 'TEXTBOOK' order by id asc", nativeQuery = true)
    List<VocabularyEntry> findByNormalizedExpressionContainingAndDictionaryStatusOrderByIdAsc(
            @Param("infix") String normalizedExpressionInfix, @Param("status") String dictionaryStatus,
            Pageable pageable);

    @Query(value = "select * from vocabulary_entry "
            + "where normalized_reading = :reading "
            + "and dictionary_status = :status and usage_scope <> 'TEXTBOOK' order by id asc limit 1", nativeQuery = true)
    Optional<VocabularyEntry> findFirstByNormalizedReadingAndDictionaryStatus(
            @Param("reading") String normalizedReading, @Param("status") String dictionaryStatus);

    @Query(value = "select * from vocabulary_entry "
            + "where normalized_reading like concat(:prefix, '%') "
            + "and dictionary_status = :status and usage_scope <> 'TEXTBOOK' order by id asc", nativeQuery = true)
    List<VocabularyEntry> findByNormalizedReadingStartingWithAndDictionaryStatusOrderByIdAsc(
            @Param("prefix") String normalizedReadingPrefix, @Param("status") String dictionaryStatus,
            Pageable pageable);

    // reading_romaji dùng collation unicode_ci (vốn không phân biệt hoa/thường) nên so sánh trực
    // tiếp để MySQL dùng index idx_vocabulary_romaji; derived query "IgnoreCase" sinh upper(...)
    // làm mất index và quét toàn bảng.
    @Query(value = "select * from vocabulary_entry where reading_romaji = :romaji "
            + "and dictionary_status = :status and usage_scope <> 'TEXTBOOK' order by id asc limit 1", nativeQuery = true)
    Optional<VocabularyEntry> findFirstByReadingRomajiIgnoreCaseAndDictionaryStatus(
            @Param("romaji") String readingRomaji, @Param("status") String dictionaryStatus);

    @Query(value = "select * from vocabulary_entry where reading_romaji like concat(:prefix, '%') "
            + "and dictionary_status = :status and usage_scope <> 'TEXTBOOK' order by id asc", nativeQuery = true)
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
            + "and dictionary_status = 'PUBLISHED' and usage_scope <> 'TEXTBOOK' order by id asc", nativeQuery = true)
    List<VocabularyEntry> findByHanViet(@Param("value") String hanViet, Pageable pageable);

    /** Từ loại của các mục có normalized_expression nằm trong {@code terms} (kiểm tra thể từ điển). */
    @Query(value = "select e.id as vocabularyId, e.normalized_expression as term, p.code as partOfSpeech "
            + "from vocabulary_entry e join vocabulary_sense s on s.vocabulary_id = e.id "
            + "join sense_part_of_speech sp on sp.sense_id = s.id "
            + "join dictionary_part_of_speech p on p.id = sp.part_of_speech_id "
            + "where e.normalized_expression in (:terms) and e.dictionary_status = 'PUBLISHED' "
            + "and e.usage_scope <> 'TEXTBOOK' "
            + "order by e.id asc", nativeQuery = true)
    List<PartOfSpeechRow> findPartsOfSpeechByExpressionIn(@Param("terms") Collection<String> terms);

    /** Như {@link #findPartsOfSpeechByExpressionIn} nhưng so với normalized_reading (query kana). */
    @Query(value = "select e.id as vocabularyId, e.normalized_reading as term, p.code as partOfSpeech "
            + "from vocabulary_entry e join vocabulary_sense s on s.vocabulary_id = e.id "
            + "join sense_part_of_speech sp on sp.sense_id = s.id "
            + "join dictionary_part_of_speech p on p.id = sp.part_of_speech_id "
            + "where e.normalized_reading in (:terms) and e.dictionary_status = 'PUBLISHED' "
            + "and e.usage_scope <> 'TEXTBOOK' "
            + "order by e.id asc", nativeQuery = true)
    List<PartOfSpeechRow> findPartsOfSpeechByReadingIn(@Param("terms") Collection<String> terms);

    interface PartOfSpeechRow {
        Long getVocabularyId();
        String getTerm();
        String getPartOfSpeech();
    }

    List<VocabularyEntry> findByMeaningContainingAndDictionaryStatusOrderByIdAsc(
            String meaningInfix, String dictionaryStatus, Pageable pageable);

    /** Published entries with the same spelling, shown to moderators to avoid duplicates. */
    List<VocabularyEntry> findTop5ByNormalizedExpressionAndDictionaryStatusAndIdNot(
            String normalizedExpression, String dictionaryStatus, Long id);
    /**
     * Tìm từ để gắn vào bài, CÓ PHÂN TRANG (bảng đang hơn 9.000 dòng, không trả hết một lần).
     * Khớp trên cột thường theo chủ ý nêu ở javadoc lớp — đây là đường đọc cho người dùng, không phải
     * đường kiểm trùng. Từ khoá rỗng thì trả toàn bộ theo trang, để giao diện mở ra đã có gì đó để xem.
     */
    @Query("""
            select v from VocabularyEntry v
            where :keyword = ''
               or v.expression like concat('%', :keyword, '%')
               or v.reading like concat('%', :keyword, '%')
               or v.readingRomaji like concat('%', :keyword, '%')
            """)
    Page<VocabularyEntry> searchForEditor(@Param("keyword") String keyword, Pageable pageable);
}
