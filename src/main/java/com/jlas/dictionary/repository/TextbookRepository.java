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

    /*
     * Sách do Lộ trình học (W0108) sinh ra cho riêng nó, nhận theo NGUỒN GỐC source_code: LP-OWNED-* (sách nội dung
     * riêng của lộ trình, bản sao Sekai N4, Quy tắc đọc Kana, J-LAS tổng hợp, J-LAS giao tiếp…) và LP-REVIEWED-*
     * (bản "Đã duyệt"). Màn Giáo trình ẩn chúng khỏi MỌI danh sách; route /textbooks/{id} vẫn mở được để lộ trình
     * link vào bài. Nhận theo source_code, KHÔNG theo cờ is_learning_path_source: cờ do dữ liệu lộ trình bật/tắt (từ
     * 09/11/2026 lộ trình chỉ đọc sách LP-OWNED-/LP-REVIEWED- của nó; sách của Giáo trình như Sekai N5, Bảng chữ cái đã
     * bỏ cờ), còn nguồn gốc sách thì chỉ source_code xác định ổn định. Đây là chỗ DUY NHẤT định nghĩa điều kiện này.
     */
    String LEARNING_PATH_OWNED_PREFIX = "LP-OWNED-";
    String LEARNING_PATH_REVIEWED_PREFIX = "LP-REVIEWED-";

    /** Điều kiện JPQL "không phải sách của lộ trình" cho textbook có alias {@code t}; ghép vào các query danh sách Giáo trình. */
    String LIBRARY_BOOK_CONDITION = " (t.sourceCode is null or (t.sourceCode not like '" + LEARNING_PATH_OWNED_PREFIX
            + "%' and t.sourceCode not like '" + LEARNING_PATH_REVIEWED_PREFIX + "%'))";

    /** Bản Java của {@link #LIBRARY_BOOK_CONDITION} cho các danh sách ghép trong service. */
    static boolean isLibraryBook(Textbook textbook) {
        String code = textbook.getSourceCode();
        return code == null
                || !(code.startsWith(LEARNING_PATH_OWNED_PREFIX) || code.startsWith(LEARNING_PATH_REVIEWED_PREFIX));
    }

    List<Textbook> findAllByOrderByIdAsc();

    List<Textbook> findByStatusOrderByIdAsc(String status);

    /**
     * Library textbooks exclude the separately presented Onyomi course, identified by its lessons, and the
     * Learning Path's own books ({@link #LIBRARY_BOOK_CONDITION}).
     */
    @Query("""
            select t from Textbook t
            where ((:includeDraft = true and t.status <> 'ARCHIVED') or t.status = :status)
              and not exists (
                select l.id from Lesson l join l.unit u
                where u.textbook = t and l.lessonType = 'AMON' and l.skill = 'ONYOMI'
              )
              and """ + LIBRARY_BOOK_CONDITION + """

            order by t.id
            """)
    List<Textbook> findLibraryTextbooks(@Param("includeDraft") boolean includeDraft,
                                        @Param("status") String status);

    @Query("""
            select count(l.id) from Lesson l join l.unit u
            where u.textbook.id = :textbookId and l.lessonType = 'AMON' and l.skill = 'ONYOMI'
            """)
    long countOnyomiLessonsByTextbookId(@Param("textbookId") Long textbookId);

    /** Cho CONTENT_CREATOR ở màn hình duyệt thường: thấy DRAFT nhưng KHÔNG thấy bản đã lưu trữ. */
    List<Textbook> findByStatusNotOrderByIdAsc(String status);

    Optional<Textbook> findByIdAndStatus(Long id, String status);

    Optional<Textbook> findBySourceCode(String sourceCode);

    /** JLPT curriculum textbooks (published, level in range); {@code id < idLessThan} excludes the local demo seed. */
    List<Textbook> findByJlptLevelInAndStatusAndIdLessThanOrderByIdAsc(
            List<String> jlptLevels, String status, Long idLessThan);

    /** [JLAS-W0108] Hướng B — chỉ textbook đã đánh cờ {@code is_learning_path_source} mới được
     * Learning Path V2 composer dùng làm nguồn sinh lộ trình; textbook khác của module Textbook
     * (dù PUBLISHED cùng cấp) không bị kéo vào, tách data lộ trình khỏi sách. */
    List<Textbook> findByJlptLevelInAndStatusAndIsLearningPathSourceTrueOrderByIdAsc(
            List<String> jlptLevels, String status);

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
            where t.jlptLevel is null and t.status = :status and t.isLearningPathSource = true
              and kt.code like 'KANA-%'
            order by t.id
            """)
    List<Textbook> findKanaFoundationByStatus(@Param("status") String status);
    Optional<Textbook> findByIdAndStatusNot(Long id, String status);

    /** Số unit theo textbook cho một tập textbook, 1 query; mỗi phần tử là [textbookId, count]. */
    @Query("""
            select u.textbook.id, count(u.id) from Unit u
            where u.textbook.id in :textbookIds
            group by u.textbook.id
            """)
    List<Object[]> countUnitsByTextbookIds(@Param("textbookIds") List<Long> textbookIds);

    /**
     * Số bài gắn tag chủ đề (tag_type SITUATION) theo textbook cho một tập textbook, 1 query;
     * mỗi phần tử là [textbookId, count]. Textbook không có bài nào gắn tag sẽ không có dòng.
     */
    @Query("""
            select l.unit.textbook.id, count(distinct l.id) from LessonTag lt
            join lt.lesson l
            join lt.tag tg
            where l.unit.textbook.id in :textbookIds and tg.tagType = :tagType
            group by l.unit.textbook.id
            """)
    List<Object[]> countSituationTaggedLessonsByTextbookIds(@Param("textbookIds") List<Long> textbookIds,
                                                            @Param("tagType") String tagType);

    /** [textbookId, tagCode] riêng biệt của các bài gắn tag loại {@code tagType} (lesson_tag), 1 query. */
    @Query("""
            select distinct l.unit.textbook.id, tg.code from LessonTag lt
            join lt.lesson l
            join lt.tag tg
            where l.unit.textbook.id in :textbookIds and tg.tagType = :tagType
            order by tg.code
            """)
    List<Object[]> findTagCodesByTextbookIds(@Param("textbookIds") List<Long> textbookIds,
                                             @Param("tagType") String tagType);
}
