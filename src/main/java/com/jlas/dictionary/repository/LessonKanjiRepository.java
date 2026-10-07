package com.jlas.dictionary.repository;

import com.jlas.dictionary.entity.LessonKanji;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** Repository cho {@link LessonKanji}. */
@Repository
public interface LessonKanjiRepository extends JpaRepository<LessonKanji, Long> {

    /** Kanji gắn tay của một bài theo thứ tự hiển thị, kèm Kanji + bộ thủ để map không phát sinh N+1. */
    @Query("""
            select lk from LessonKanji lk
              join fetch lk.kanji k
              left join fetch k.radical
            where lk.lesson.id = :lessonId
            order by lk.orderIndex, lk.id
            """)
    List<LessonKanji> findByLessonIdWithKanji(@Param("lessonId") Long lessonId);

    Optional<LessonKanji> findByLessonIdAndKanjiId(Long lessonId, Long kanjiId);

    /** order_index lớn nhất trong một bài; null khi bài chưa gắn kanji nào. Dùng để thêm vào cuối. */
    @Query("select max(lk.orderIndex) from LessonKanji lk where lk.lesson.id = :lessonId")
    Integer findMaxOrderIndexByLessonId(@Param("lessonId") Long lessonId);
}
