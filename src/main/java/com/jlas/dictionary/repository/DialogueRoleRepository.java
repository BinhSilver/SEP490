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

    List<DialogueRole> findByLessonIdOrderByCodeAsc(Long lessonId);

    /** uk_dialogue_role_lesson_code: mã vai duy nhất trong phạm vi bài — kiểm trước để trả 409 có thông báo. */
    boolean existsByLessonIdAndCode(Long lessonId, String code);

    /**
     * Số lời thoại đang gắn vai này. fk_dialogue_detail_role là ON DELETE SET NULL nên xoá vai còn lời
     * sẽ khiến các lời mất tên người nói trong im lặng — service dùng số này để chặn.
     */
    @Query("select count(d.contentItemId) from DialogueLineDetail d where d.role.id = :roleId")
    long countLinesByRoleId(@Param("roleId") Long roleId);
}
