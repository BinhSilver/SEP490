package com.jlas.dictionary.repository;

import com.jlas.dictionary.entity.ContentItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/** Repository cho {@link ContentItem}. */
@Repository
public interface ContentItemRepository extends JpaRepository<ContentItem, Long> {

    /**
     * Content item của một lesson theo loại (READING/GRAMMAR/LISTENING), kèm section (fetch join),
     * thứ tự section rồi order_index, 1 query.
     */
    @Query("""
            select ci from ContentItem ci
              join fetch ci.section s
            where s.lesson.id = :lessonId and ci.contentType = :contentType
            order by s.orderIndex, ci.orderIndex, ci.id
            """)
    List<ContentItem> findByLessonIdAndContentType(@Param("lessonId") Long lessonId,
                                                   @Param("contentType") String contentType);
}
