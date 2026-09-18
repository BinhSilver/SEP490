package com.jlas.dictionary.repository;

import com.jlas.dictionary.entity.DialogueLineDetail;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/** Repository cho {@link DialogueLineDetail}. */
@Repository
public interface DialogueLineDetailRepository extends JpaRepository<DialogueLineDetail, Long> {

    /**
     * Toàn bộ lời thoại của một lesson theo thứ tự section rồi order_index của content_item,
     * kèm content_item, section, vai và giọng của vai (fetch join), 1 query.
     */
    @Query("""
            select d from DialogueLineDetail d
              join fetch d.contentItem ci
              join fetch ci.section s
              left join fetch d.role r
              left join fetch r.voice
            where s.lesson.id = :lessonId
            order by s.orderIndex, ci.orderIndex, ci.id
            """)
    List<DialogueLineDetail> findByLessonId(@Param("lessonId") Long lessonId);
}
