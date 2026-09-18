package com.jlas.dictionary.repository;

import com.jlas.dictionary.entity.Lesson;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** Repository cho {@link Lesson}. */
@Repository
public interface LessonRepository extends JpaRepository<Lesson, Long> {

    /**
     * Lesson kèm unit, textbook của unit và knowledge tag (fetch join, 1 query)
     * để dựng header nội dung bài và kiểm tra trạng thái duyệt của cả lesson lẫn textbook.
     */
    @Query("""
            select l from Lesson l
              join fetch l.unit u
              join fetch u.textbook
              join fetch l.knowledgeTag
            where l.id = :lessonId
            """)
    Optional<Lesson> findWithUnitById(@Param("lessonId") Long lessonId);

    /** Toàn bộ lesson của một textbook (mọi trạng thái), kèm unit, 1 query. */
    @Query("""
            select l from Lesson l
              join fetch l.unit u
            where u.textbook.id = :textbookId
            order by u.orderIndex, u.id, l.orderIndex, l.id
            """)
    List<Lesson> findByTextbookId(@Param("textbookId") Long textbookId);

    /** Lesson của một textbook lọc theo trạng thái, kèm unit, 1 query. */
    @Query("""
            select l from Lesson l
              join fetch l.unit u
            where u.textbook.id = :textbookId and l.status = :status
            order by u.orderIndex, u.id, l.orderIndex, l.id
            """)
    List<Lesson> findByTextbookIdAndStatus(@Param("textbookId") Long textbookId,
                                           @Param("status") String status);

    /**
     * Dành cho Learning Path: bài học learner được xem của một CẤP ĐỘ JLPT (N5, N4, ...), kèm unit,
     * textbook và knowledge tag (fetch join, 1 query), thứ tự textbook → unit → bài.
     * <p>
     * Thay cho {@code findByKnowledgeTagName(targetLevel)} — cách đó hiểu nhầm {@code lesson.knowledge_tag}
     * là cấp độ. Theo schema, knowledge_tag là ĐIỂM KIẾN THỨC chính của bài (dùng cho weakness /
     * recommendation), mỗi bài một tag riêng ("N5 bài 1: Giới thiệu bản thân..."), nên không có tag nào
     * tên "N5"/"N4" và query đó luôn trả rỗng. Cấp độ thật nằm ở {@code textbook.jlpt_level}.
     * Chỉ trả bài PUBLISHED trong textbook PUBLISHED để lộ trình không chứa bài learner mở không được.
     */
    @Query("""
            select l from Lesson l
              join fetch l.unit u
              join fetch u.textbook t
              join fetch l.knowledgeTag
            where t.jlptLevel = :jlptLevel and l.status = 'PUBLISHED' and t.status = 'PUBLISHED'
            order by t.id, u.orderIndex, u.id, l.orderIndex, l.id
            """)
    List<Lesson> findPublishedByTextbookJlptLevel(@Param("jlptLevel") String jlptLevel);

    /** Toàn bộ bài gắn tag (mọi trạng thái), kèm unit + textbook, thứ tự textbook → unit → bài. 1 query. */
    @Query("""
            select l from LessonTag lt
              join lt.lesson l
              join fetch l.unit u
              join fetch u.textbook t
            where lt.tag.code = :tagCode
            order by t.id, u.orderIndex, u.id, l.orderIndex, l.id
            """)
    List<Lesson> findByTagCode(@Param("tagCode") String tagCode);

    /** Bài gắn tag mà learner được xem: lesson và textbook đều ở trạng thái :status. 1 query. */
    @Query("""
            select l from LessonTag lt
              join lt.lesson l
              join fetch l.unit u
              join fetch u.textbook t
            where lt.tag.code = :tagCode and l.status = :status and t.status = :status
            order by t.id, u.orderIndex, u.id, l.orderIndex, l.id
            """)
    List<Lesson> findByTagCodeAndStatus(@Param("tagCode") String tagCode, @Param("status") String status);
}
