package com.jlas.dictionary.repository;

import com.jlas.dictionary.entity.Tag;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** Repository cho {@link Tag}. */
@Repository
public interface TagRepository extends JpaRepository<Tag, Long> {

    List<Tag> findByTagTypeOrderByCodeAsc(String tagType);

    Optional<Tag> findByCodeAndTagType(String code, String tagType);

    /** Số bài (mọi trạng thái) theo từng tag của một loại; mỗi phần tử là [tagId, count]. 1 query. */
    @Query("""
            select lt.tag.id, count(lt.lesson.id) from LessonTag lt
              join lt.tag tg
            where tg.tagType = :tagType
            group by lt.tag.id
            """)
    List<Object[]> countLessonsByTagType(@Param("tagType") String tagType);

    /** Số bài learner được xem (lesson và textbook đều ở trạng thái :status) theo từng tag. 1 query. */
    @Query("""
            select lt.tag.id, count(l.id) from LessonTag lt
              join lt.tag tg
              join lt.lesson l
              join l.unit u
              join u.textbook t
            where tg.tagType = :tagType and l.status = :status and t.status = :status
            group by lt.tag.id
            """)
    List<Object[]> countLessonsByTagTypeAndStatus(@Param("tagType") String tagType, @Param("status") String status);
}
