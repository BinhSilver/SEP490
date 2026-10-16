package com.jlas.dictionary.repository;

import com.jlas.dictionary.entity.VocabularyGloss;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/** Repository cho {@link VocabularyGloss}. */
@Repository
public interface VocabularyGlossRepository extends JpaRepository<VocabularyGloss, Long> {

    /** Load gloss của nhiều sense cùng lúc, tránh N+1 khi map danh sách kết quả search. */
    List<VocabularyGloss> findBySense_IdIn(List<Long> senseIds);

    // Các tier tìm theo nghĩa dưới đây so khớp đúng dấu tiếng Việt (utf8mb4_0900_as_ci): collation
    // mặc định unicode_ci coi "an", "ăn", "ân" là như nhau. Điều kiện theo collation cột đứng trước
    // để MySQL dùng index idx_vocabulary_gloss_text, sau đó mới lọc lại đúng dấu.

    /** Gloss trùng khớp hoàn toàn với từ cần tìm, ví dụ "ăn" -> 食べる. */
    @Query(value = "select s.vocabulary_id from vocabulary_gloss g "
            + "join vocabulary_sense s on s.id = g.sense_id "
            + "join vocabulary_entry ve on ve.id = s.vocabulary_id "
            + "where g.language_code in ('vie', 'vi', 'eng', 'en') and g.gloss_text = :text "
            + "and g.gloss_text collate utf8mb4_0900_as_ci = :text "
            + "and ve.dictionary_status = 'PUBLISHED' "
            + "group by s.vocabulary_id order by s.vocabulary_id asc", nativeQuery = true)
    List<Long> findVocabularyIdsByGlossExact(@Param("text") String text, Pageable pageable);

    /** Gloss bắt đầu bằng từ cần tìm. Tham số đã escape ký tự đại diện của LIKE. */
    @Query(value = "select s.vocabulary_id from vocabulary_gloss g "
            + "join vocabulary_sense s on s.id = g.sense_id "
            + "join vocabulary_entry ve on ve.id = s.vocabulary_id "
            + "where g.language_code in ('vie', 'vi', 'eng', 'en') "
            + "and g.gloss_text like concat(:prefix, '%') "
            + "and g.gloss_text collate utf8mb4_0900_as_ci like concat(:prefix, '%') "
            + "and ve.dictionary_status = 'PUBLISHED' "
            + "group by s.vocabulary_id order by s.vocabulary_id asc", nativeQuery = true)
    List<Long> findVocabularyIdsByGlossPrefix(@Param("prefix") String likeEscapedPrefix, Pageable pageable);

    /**
     * Gloss chứa từ cần tìm ở đầu một từ ("ăn" khớp "đồ ăn" nhưng không khớp "khăn"). Đây là tier
     * quét toàn bảng duy nhất: STRAIGHT_JOIN buộc đọc gloss trước (nếu không MySQL duyệt sense theo
     * thứ tự id rồi tra gloss từng dòng, chậm ~10 lần), và LIKE theo collation cột lọc thô trước khi
     * phải chuyển collation để so đúng dấu.
     */
    @Query(value = "select s.vocabulary_id from vocabulary_gloss g "
            + "straight_join vocabulary_sense s on s.id = g.sense_id "
            + "straight_join vocabulary_entry ve on ve.id = s.vocabulary_id "
            + "where g.gloss_text like concat('%', :word, '%') "
            + "and concat(' ', g.gloss_text) collate utf8mb4_0900_as_ci like concat('% ', :word, '%') "
            + "and ve.dictionary_status = 'PUBLISHED' "
            + "group by s.vocabulary_id order by s.vocabulary_id asc", nativeQuery = true)
    List<Long> findVocabularyIdsByGlossWord(@Param("word") String likeEscapedWord, Pageable pageable);
}
