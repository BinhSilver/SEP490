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
 * <p>Các cột tiếng Nhật (normalized_expression, normalized_reading) và toàn bộ schema JLAS2-19
 * đều dùng collation {@code utf8mb4_unicode_ci} (mặc định toàn dự án). Collation này coi các cặp
 * thanh/trọc/bán trọc âm (は/ば/ぱ, か/が, た/だ...) là tương đương khi so sánh chuỗi, nên so khớp
 * mặc định có thể lẫn "はは" với "ばば". Các query tra cứu chính xác/prefix theo Kanji/Kana dưới
 * đây ép {@code COLLATE utf8mb4_bin} ngay tại câu query (không đổi collation cột/schema) để so
 * khớp đúng byte, còn romaji/meaning (ASCII/Latin) không bị ảnh hưởng nên giữ derived query.</p>
 *
 * <p>Ngoại lệ có chủ ý: {@link #searchForEditor} (tìm từ để gắn vào bài ở màn biên soạn) giữ so khớp
 * mặc định trên cột {@code expression} / {@code reading}, vì bỏ qua dakuten (バス = パス) ở đây là
 * điểm lợi: người biên soạn gõ thiếu dấu đục vẫn tìm ra từ. Kiểm trùng khi ghi thì phải so khớp đúng
 * byte như các query tra cứu chính xác ở trên.</p>
 */
@Repository
public interface VocabularyEntryRepository extends JpaRepository<VocabularyEntry, Long> {

    List<VocabularyEntry> findByReadingContainingIgnoreCase(String keyword);

    List<VocabularyEntry> findByIdIn(List<Long> ids);

    /**
     * Kiểm trùng trước khi tạo từ mới. Cố ý so sánh bằng collation của chính cột (KHÔNG ép utf8mb4_bin)
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
