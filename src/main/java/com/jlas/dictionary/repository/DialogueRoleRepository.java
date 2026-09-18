package com.jlas.dictionary.repository;

import com.jlas.dictionary.entity.DialogueRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/** Repository cho {@link DialogueRole}. */
@Repository
public interface DialogueRoleRepository extends JpaRepository<DialogueRole, Long> {

    /** Vai diễn của một lesson kèm giọng mặc định (fetch join), 1 query. */
    @Query("""
            select r from DialogueRole r
              left join fetch r.voice
            where r.lesson.id = :lessonId
            order by r.id
            """)
    List<DialogueRole> findByLessonId(@Param("lessonId") Long lessonId);
}
