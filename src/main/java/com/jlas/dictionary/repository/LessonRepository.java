package com.jlas.dictionary.repository;

import com.jlas.dictionary.entity.Lesson;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Repository cho {@link Lesson}. */
@Repository
public interface LessonRepository extends JpaRepository<Lesson, Long> {

    Optional<Lesson> findByUnitIdAndSourceCode(Long unitId, String sourceCode);

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

    /** [JLAS2-17] Nhiều lesson kèm unit + textbook (1 query) cho công bố / ẩn hàng loạt. */
    @Query("""
            select l from Lesson l
              join fetch l.unit u
              join fetch u.textbook
            where l.id in :ids
            """)
    List<Lesson> findWithUnitByIdIn(@Param("ids") java.util.Collection<Long> ids);

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
              and """ + TextbookRepository.LIBRARY_BOOK_CONDITION + """

            order by t.id, u.orderIndex, u.id, l.orderIndex, l.id
            """)
    List<Lesson> findByTagCodeAndStatus(@Param("tagCode") String tagCode, @Param("status") String status);

    /** [JLAS-W0108] Bài luyện đọc/nghe đã xuất bản ở một cấp độ (skill READING/LISTENING) cho warm-up. */
    @Query("""
            select l from Lesson l
              join fetch l.unit u
              join fetch u.textbook t
              join fetch l.knowledgeTag
            where t.jlptLevel = :jlptLevel and l.skill = :skill
              and l.status = 'PUBLISHED' and t.status = 'PUBLISHED'
            order by t.id, u.orderIndex, u.id, l.orderIndex, l.id
            """)
    List<Lesson> findPublishedByJlptLevelAndSkill(@Param("jlptLevel") String jlptLevel,
                                                  @Param("skill") String skill);

    /** [JLAS-W0108] Bài luyện đọc/nghe đã xuất bản ở MỌI cấp độ — fallback khi cấp độ chính xác thiếu học liệu. */
    @Query("""
            select l from Lesson l
              join fetch l.unit u
              join fetch u.textbook t
              join fetch l.knowledgeTag
            where l.skill = :skill and l.status = 'PUBLISHED' and t.status = 'PUBLISHED'
            order by t.id, u.orderIndex, u.id, l.orderIndex, l.id
            """)
    List<Lesson> findPublishedBySkill(@Param("skill") String skill);

    /**
     * [JLAS-W0108] Bài đã xuất bản ở một cấp độ có chứa nội dung {@code content_item} đúng loại
     * (READING/LISTENING). N5/N4 không có bài chuyên READING/LISTENING — phần đọc/nghe nằm trong
     * {@code content_item} của bài tổng hợp GRAMMAR, nên đây là nguồn dữ liệu thật để warm-up đọc/nghe
     * thay vì bịa bài học hay gắn nhầm dữ liệu vào sách.
     */
    @Query("""
            select distinct l from ContentItem ci
              join ci.section s
              join s.lesson l
              join fetch l.unit u
              join fetch u.textbook t
              join fetch l.knowledgeTag
            where ci.contentType = :contentType
              and t.jlptLevel = :jlptLevel
              and l.status = 'PUBLISHED' and t.status = 'PUBLISHED'
            order by t.id, u.orderIndex, u.id, l.orderIndex, l.id
            """)
    List<Lesson> findPublishedByJlptLevelAndContentType(@Param("jlptLevel") String jlptLevel,
                                                        @Param("contentType") String contentType);
    /**
     * Cho CONTENT_CREATOR ở màn hình duyệt thường: bài của một textbook trừ bản đã lưu trữ.
     * Chỉ lọc theo lesson.status; trạng thái textbook do lời gọi phía trên đã kiểm.
     */
    @Query("""
            select l from Lesson l
              join fetch l.unit u
            where u.textbook.id = :textbookId and l.status <> :excludedStatus
            order by u.orderIndex, u.id, l.orderIndex, l.id
            """)
    List<Lesson> findByTextbookIdAndStatusNot(@Param("textbookId") Long textbookId,
                                              @Param("excludedStatus") String excludedStatus);

    /**
     * Cho CONTENT_CREATOR ở trang chủ đề: bài gắn tag, trừ bản đã lưu trữ ở CẢ HAI tầng —
     * bài nằm trong giáo trình đã lưu trữ cũng phải biến mất khỏi danh sách.
     */
    @Query("""
            select l from LessonTag lt
              join lt.lesson l
              join fetch l.unit u
              join fetch u.textbook t
            where lt.tag.code = :tagCode and l.status <> :excludedStatus and t.status <> :excludedStatus
              and """ + TextbookRepository.LIBRARY_BOOK_CONDITION + """

            order by t.id, u.orderIndex, u.id, l.orderIndex, l.id
            """)
    List<Lesson> findByTagCodeAndStatusNot(@Param("tagCode") String tagCode,
                                           @Param("excludedStatus") String excludedStatus);

    /** Bài của một unit theo thứ tự hiện tại, MỌI trạng thái. Dùng khi đánh số lại order_index. */
    List<Lesson> findByUnitIdOrderByOrderIndexAscIdAsc(Long unitId);

    /** order_index lớn nhất trong một unit; null khi unit chưa có bài nào. Dùng để thêm vào cuối. */
    @Query("select max(l.orderIndex) from Lesson l where l.unit.id = :unitId")
    Integer findMaxOrderIndexByUnitId(@Param("unitId") Long unitId);

    /* ── Đếm phụ thuộc trước khi xoá cứng một bài ────────────────────────────
     * MỌI khoá ngoại trỏ tới lesson(id) đều là ON DELETE CASCADE, nên một lệnh DELETE sẽ âm thầm
     * quét sạch cả tiến độ học viên, lộ trình học và quiz. Ba query dưới đây đếm từng nhóm để service
     * chặn trước bằng 409 có giải thích, thay vì để DB xoá mất dữ liệu không khôi phục được.
     * Dùng native query vì các bảng này thuộc module khác (learning / assessment), không có entity
     * trong package dictionary — và chỉ ĐỌC số lượng, không đụng vào dữ liệu assessment.
     */

    /** Số học viên đã có tiến độ ở bài này. */
    @Query(value = "select count(*) from lesson_progress where lesson_id = :lessonId", nativeQuery = true)
    long countLearnerProgress(@Param("lessonId") Long lessonId);

    /** Số lần bài này nằm trong lộ trình học của học viên. */
    @Query(value = "select count(*) from user_learning_path_item where lesson_id = :lessonId", nativeQuery = true)
    long countLearningPathItems(@Param("lessonId") Long lessonId);

    /** Số quiz gắn với bài này (bảng assessment_*, chỉ đếm). */
    @Query(value = "select count(*) from assessment_quiz where lesson_id = :lessonId", nativeQuery = true)
    long countAssessmentQuizzes(@Param("lessonId") Long lessonId);

    /**
     * Câu hỏi luyện tập của bài trong các quiz gắn với bài (assessment_*, chỉ ĐỌC), theo thứ tự quiz rồi thứ tự câu.
     * Cùng quy tắc với bài nháp: người học chỉ thấy quiz PUBLISHED + câu đang bật; includeDraft (CONTENT_CREATOR)
     * thấy cả quiz/câu nháp để duyệt nội dung. Cột: id, order_index, question_text, option_1..option_4,
     * correct_option, explanation, context_json, answer_text, passage_html, image_url. Phương án đầy đủ (câu 5–9
     * phương án, cờ is_correct của câu nhiều đáp án) nằm trong context_json.options.
     */
    @Query(value = """
            select q.id, qq.order_index, q.question_text, q.option_1, q.option_2, q.option_3, q.option_4,
                   q.correct_option, q.explanation, q.context_json, q.answer_text, q.passage_html, q.image_url
            from assessment_quiz z
              join assessment_quiz_question qq on qq.quiz_id = z.id
              join assessment_question q on q.id = qq.question_id
            where z.lesson_id = :lessonId
              and (:includeDraft = true or (z.status = 'PUBLISHED' and q.is_active = 1))
            order by z.id, qq.order_index, q.id
            """, nativeQuery = true)
    List<Object[]> findPracticeQuestionRows(@Param("lessonId") Long lessonId,
                                            @Param("includeDraft") boolean includeDraft);

    /** Bài có quiz LESSON đã xuất bản không — cùng điều kiện với LessonQuizRepository.findPublishedLessonQuiz. */
    @Query(value = "select count(*) from assessment_quiz where lesson_id = :lessonId "
            + "and quiz_type = 'LESSON' and status = 'PUBLISHED'", nativeQuery = true)
    long countPublishedLessonQuizzes(@Param("lessonId") Long lessonId);

    /** Tổng nội dung đã biên soạn trong bài: phần nội dung, mẫu ngữ pháp, vai hội thoại, quy tắc chuyển âm, kanji gắn tay. */
    @Query(value = """
            select (select count(*) from lesson_section  where lesson_id = :lessonId)
                 + (select count(*) from grammar_point   where lesson_id = :lessonId)
                 + (select count(*) from dialogue_role   where lesson_id = :lessonId)
                 + (select count(*) from conversion_rule where lesson_id = :lessonId)
                 + (select count(*) from lesson_kanji    where lesson_id = :lessonId)
            """, nativeQuery = true)
    long countEditorialContent(@Param("lessonId") Long lessonId);

    /*
     * [JLAS2-17] Quiz LUYỆN TẬP của bài = quiz_type 'LESSON' gắn bài, id trong dải seed nguồn ngoài (vnjpclub
     * 1.000.001–1.999.999, Minna 3.000.001–3.999.999; seed đặt tên "Luyện tập: …"). Trạng thái của nó đi theo bài để
     * học viên thấy đúng nội dung như CONTENT_CREATOR. Quiz lộ trình (quiz LESSON id < 1.000.000, UNIT_FINAL) không đụng.
     * Viết dạng subquery (không UPDATE … JOIN) để chạy được cả MySQL lẫn H2 của test.
     */

    /** [JLAS2-17] Nguồn nội dung của bài (content_source cấp LESSON) — trang học hiện dòng "Nguồn nội dung: …". */
    @Query(value = "select source_code from content_source where entity_type = 'LESSON' and entity_id = :lessonId"
            + " order by id limit 1", nativeQuery = true)
    Optional<String> findLessonSourceCode(@Param("lessonId") Long lessonId);

    /** Bật mọi câu hỏi của quiz luyện tập thuộc các bài; trả số câu vừa bật. */
    @Modifying
    @Query(value = """
            update assessment_question set is_active = true
            where is_active = false and id in (
              select qq.question_id from assessment_quiz_question qq
                join assessment_quiz z on z.id = qq.quiz_id
              where z.lesson_id in (:lessonIds) and z.quiz_type = 'LESSON'
                and (z.id between 1000001 and 1999999 or z.id between 3000001 and 3999999))
            """, nativeQuery = true)
    int activatePracticeQuestions(@Param("lessonIds") Collection<Long> lessonIds);

    /** Đặt trạng thái quiz luyện tập của các bài (PUBLISHED khi bài công bố, DRAFT khi bài ẩn / lưu trữ); trả số quiz đổi. */
    @Modifying
    @Query(value = """
            update assessment_quiz set status = :status
            where lesson_id in (:lessonIds) and quiz_type = 'LESSON' and status <> :status
              and (id between 1000001 and 1999999 or id between 3000001 and 3999999)
            """, nativeQuery = true)
    int updatePracticeQuizStatus(@Param("lessonIds") Collection<Long> lessonIds, @Param("status") String status);
}
