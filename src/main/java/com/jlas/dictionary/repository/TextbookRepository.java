package com.jlas.dictionary.repository;

import com.jlas.dictionary.entity.Textbook;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** Repository cho {@link Textbook}. */
@Repository
public interface TextbookRepository extends JpaRepository<Textbook, Long> {

    List<Textbook> findAllByOrderByIdAsc();

    List<Textbook> findByStatusOrderByIdAsc(String status);

    /** Library textbooks exclude the separately presented Onyomi course, identified by its lessons. */
    @Query("""
            select t from Textbook t
            where (:includeDraft = true or t.status = :status)
              and not exists (
                select l.id from Lesson l join l.unit u
                where u.textbook = t and l.lessonType = 'AMON' and l.skill = 'ONYOMI'
              )
            order by t.id
            """)
    List<Textbook> findLibraryTextbooks(@Param("includeDraft") boolean includeDraft,
                                        @Param("status") String status);

    @Query("""
            select count(l.id) from Lesson l join l.unit u
            where u.textbook.id = :textbookId and l.lessonType = 'AMON' and l.skill = 'ONYOMI'
            """)
    long countOnyomiLessonsByTextbookId(@Param("textbookId") Long textbookId);

    Optional<Textbook> findByIdAndStatus(Long id, String status);

    /** JLPT curriculum textbooks (published, level in range); {@code id < idLessThan} excludes the local demo seed. */
    List<Textbook> findByJlptLevelInAndStatusAndIdLessThanOrderByIdAsc(
            List<String> jlptLevels, String status, Long idLessThan);

    /** Local demo-seed textbooks (published, level in range, id >= idGreaterThanEqual); temporary fallback until real content is published. */
    List<Textbook> findByJlptLevelInAndStatusAndIdGreaterThanEqualOrderByIdAsc(
            List<String> jlptLevels, String status, Long idGreaterThanEqual);

    /**
     * [JLAS-W0108] Foundation (kana/alphabet) textbooks cho from-zero warm-up: sách đã xuất bản,
     * không có cấp JLPT và có bài gắn tag {@code KANA-*} (Hiragana/Katakana). Lọc theo tag để LOẠI
     * các sách hội thoại chuyên ngành (IT/du lịch/đời sống) cũng có {@code jlpt_level IS NULL} —
     * chúng thuộc goal BA/TRAVEL/DAILY_LIFE, không phải nền tảng bảng chữ cái của lộ trình JLPT.
     */
    @Query("""
            select distinct t from Lesson l
              join l.unit u
              join u.textbook t
              join l.knowledgeTag kt
            where t.jlptLevel is null and t.status = :status and kt.code like 'KANA-%'
            order by t.id
            """)
    List<Textbook> findKanaFoundationByStatus(@Param("status") String status);

    /** Số unit theo textbook cho một tập textbook, 1 query; mỗi phần tử là [textbookId, count]. */
    @Query("""
            select u.textbook.id, count(u.id) from Unit u
            where u.textbook.id in :textbookIds
            group by u.textbook.id
            """)
    List<Object[]> countUnitsByTextbookIds(@Param("textbookIds") List<Long> textbookIds);
}
