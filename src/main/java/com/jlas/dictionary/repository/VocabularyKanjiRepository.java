package com.jlas.dictionary.repository;

import com.jlas.dictionary.entity.VocabularyEntry;
import com.jlas.dictionary.entity.VocabularyKanji;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

/**
 * Repository cho {@link VocabularyKanji}. Dùng bảng nối này để tìm vocabulary
 * chứa một Kanji thông qua quan hệ dữ liệu (idx_vocabulary_kanji_prefix /
 * idx_vocabulary_kanji_lookup), thay vì LIKE '%kanji%' trên expression.
 *
 * <p>{@code kanji.character} là cột utf8mb4_bin (migration 059) nên điều kiện so sánh trực tiếp
 * dùng được index uk_kanji_character; bọc {@code BINARY} sẽ làm MySQL quét toàn bảng nối.</p>
 */
@Repository
public interface VocabularyKanjiRepository
        extends JpaRepository<VocabularyKanji, VocabularyKanji.VocabularyKanjiId> {

    /**
     * Liên kết từ ↔ Kanji của một tập từ vựng, kèm Kanji và bộ thủ (fetch join), 1 query.
     * Từ kết quả này service suy ra cả tập Kanji của bài lẫn các từ chứa từng chữ.
     */
    @Query("""
            select vk from VocabularyKanji vk
              join fetch vk.kanji k
              left join fetch k.radical
            where vk.id.vocabularyId in :vocabularyIds
            order by k.id, vk.id.vocabularyId, vk.id.characterPosition
            """)
    List<VocabularyKanji> findByVocabularyIdIn(@Param("vocabularyIds") Collection<Long> vocabularyIds);

    /** Vocabulary mà Kanji đứng ở vị trí đầu tiên (character_position = 1). */
    @Query(value = "select ve.* from vocabulary_kanji vk "
            + "join kanji k on k.id = vk.kanji_id "
            + "join vocabulary_entry ve on ve.id = vk.vocabulary_id "
            + "where k.`character` = :kanjiChar and vk.character_position = 1 "
            + "and ve.dictionary_status = 'PUBLISHED' "
            + "order by vk.vocabulary_id asc", nativeQuery = true)
    List<VocabularyEntry> findVocabularyStartingWithKanji(
            @Param("kanjiChar") String character, Pageable pageable);

    /**
     * Vocabulary có expression chứa {@code infix}, tìm qua một Kanji neo của chính infix đó: mọi từ
     * chứa infix đều chứa Kanji neo, nên kết quả trùng với LIKE '%infix%' trên toàn bảng nhưng chỉ
     * phải lọc vài nghìn từ có Kanji neo thay vì quét ~280 nghìn dòng (食べる: ~5 ms thay vì ~400 ms).
     */
    @Query(value = "select distinct ve.* from vocabulary_kanji vk "
            + "join kanji k on k.id = vk.kanji_id "
            + "join vocabulary_entry ve on ve.id = vk.vocabulary_id "
            + "where k.`character` = :anchor "
            + "and ve.normalized_expression like concat('%', :infix, '%') "
            + "and ve.dictionary_status = 'PUBLISHED' "
            + "order by ve.id asc", nativeQuery = true)
    List<VocabularyEntry> findVocabularyContainingViaKanji(
            @Param("anchor") String anchorKanji, @Param("infix") String likeEscapedInfix, Pageable pageable);

    /** Vocabulary chứa Kanji nhưng không ở vị trí đầu. */
    @Query(value = "select ve.* from vocabulary_kanji vk "
            + "join kanji k on k.id = vk.kanji_id "
            + "join vocabulary_entry ve on ve.id = vk.vocabulary_id "
            + "where k.`character` = :kanjiChar and vk.character_position > 1 "
            + "and ve.dictionary_status = 'PUBLISHED' "
            + "order by vk.vocabulary_id asc", nativeQuery = true)
    List<VocabularyEntry> findVocabularyContainingKanjiNotFirst(
            @Param("kanjiChar") String character, Pageable pageable);

    /** Composition Kanji (đã join fetch Kanji) của nhiều vocabulary cùng lúc, tránh N+1. */
    @Query("select vk from VocabularyKanji vk join fetch vk.kanji "
            + "where vk.vocabularyEntry.id in :vocabularyIds "
            + "order by vk.id.vocabularyId asc, vk.id.characterPosition asc")
    List<VocabularyKanji> findCompositionByVocabularyIds(
            @Param("vocabularyIds") List<Long> vocabularyIds);

    /** Vocabulary chứa một Kanji cho trước, dùng cho trang chi tiết Kanji ("Related Vocabulary"). */
    @Query(value = "select ve.* from vocabulary_kanji vk "
            + "join kanji k on k.id = vk.kanji_id "
            + "join vocabulary_entry ve on ve.id = vk.vocabulary_id "
            + "where k.`character` = :kanjiChar "
            + "and ve.dictionary_status = 'PUBLISHED' "
            + "order by vk.character_position asc, vk.vocabulary_id asc", nativeQuery = true)
    List<VocabularyEntry> findVocabularyByKanjiCharacter(
            @Param("kanjiChar") String character, Pageable pageable);
}
