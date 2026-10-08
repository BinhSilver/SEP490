package com.jlas.dictionary.repository;

import com.jlas.dictionary.entity.Unit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/** Repository cho {@link Unit}. */
@Repository
public interface UnitRepository extends JpaRepository<Unit, Long> {

    List<Unit> findByTextbookIdOrderByOrderIndexAscIdAsc(Long textbookId);

    /**
     * Số bài thuộc một unit, MỌI trạng thái (kể cả ARCHIVED). Dùng để chặn xoá cứng unit còn bài:
     * bài đã lưu trữ vẫn là dữ liệu phải giữ, không được coi unit là rỗng chỉ vì bài đang ẩn.
     */
    @Query("select count(l.id) from Lesson l where l.unit.id = :unitId")
    long countLessonsByUnitId(@Param("unitId") Long unitId);

    /** order_index lớn nhất trong một giáo trình; null khi giáo trình chưa có unit nào. Dùng để thêm vào cuối. */
    @Query("select max(u.orderIndex) from Unit u where u.textbook.id = :textbookId")
    Integer findMaxOrderIndexByTextbookId(@Param("textbookId") Long textbookId);

    /**
     * Số quiz gắn thẳng vào unit (assessment_quiz.unit_id, ví dụ bài kiểm tra cuối phần).
     * fk_assessment_quiz_unit cũng là ON DELETE CASCADE, nên một unit RỖNG BÀI vẫn có thể kéo theo quiz
     * khi bị xoá — phải đếm riêng, không suy ra từ số bài. Chỉ đọc, không đụng dữ liệu assessment.
     */
    @Query(value = "select count(*) from assessment_quiz where unit_id = :unitId", nativeQuery = true)
    long countAssessmentQuizzes(@Param("unitId") Long unitId);
}
