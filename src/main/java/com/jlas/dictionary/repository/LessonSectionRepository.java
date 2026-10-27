package com.jlas.dictionary.repository;

import com.jlas.dictionary.entity.LessonSection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/** Repository cho {@link LessonSection} — phần nội dung bên trong một bài học. */
@Repository
public interface LessonSectionRepository extends JpaRepository<LessonSection, Long> {

    List<LessonSection> findByLessonIdOrderByOrderIndexAscIdAsc(Long lessonId);

    /**
     * Số mục nội dung thuộc một phần. Dùng để chặn xoá cứng phần còn nội dung:
     * fk_content_item_section là ON DELETE CASCADE nên xoá phần sẽ kéo theo toàn bộ content_item,
     * và từ đó là câu ví dụ, lời hội thoại, liên kết từ vựng của bài.
     */
    @Query("select count(c.id) from ContentItem c where c.section.id = :sectionId")
    long countContentItemsBySectionId(@Param("sectionId") Long sectionId);

    /** order_index lớn nhất trong một bài; null khi bài chưa có phần nào. Dùng để thêm vào cuối. */
    @Query("select max(s.orderIndex) from LessonSection s where s.lesson.id = :lessonId")
    Integer findMaxOrderIndexByLessonId(@Param("lessonId") Long lessonId);
}
