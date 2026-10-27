package com.jlas.dictionary.repository;

import com.jlas.dictionary.entity.GrammarPoint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/** Repository cho {@link GrammarPoint}. */
@Repository
public interface GrammarPointRepository extends JpaRepository<GrammarPoint, Long> {

    List<GrammarPoint> findByLesson_StatusOrderByIdAsc(String status);

    List<GrammarPoint> findByLessonIdOrderByOrderIndexAsc(Long lessonId);

    /** Thứ tự ổn định khi order_index trùng nhau, dùng cho màn hình biên soạn và lúc đánh số lại. */
    List<GrammarPoint> findByLessonIdOrderByOrderIndexAscIdAsc(Long lessonId);

    /** order_index lớn nhất trong một bài; null khi bài chưa có mẫu nào. Dùng để thêm vào cuối. */
    @Query("select max(g.orderIndex) from GrammarPoint g where g.lesson.id = :lessonId")
    Integer findMaxOrderIndexByLessonId(@Param("lessonId") Long lessonId);
}
