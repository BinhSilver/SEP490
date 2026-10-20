package com.jlas.dictionary.repository;

import com.jlas.dictionary.entity.ContentVocabulary;
import com.jlas.dictionary.entity.VocabularyEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/** Repository cho {@link ContentVocabulary}. */
@Repository
public interface ContentVocabularyRepository extends JpaRepository<ContentVocabulary, ContentVocabulary.ContentVocabularyId> {

    /**
     * Toàn bộ từ vựng thuộc một lesson (đi qua content_item → lesson_section), 1 query.
     * JOIN chỉ theo khoá số, không đụng cột normalized_* (collation utf8mb4_0900_as_cs).
     */
    @Query("""
            select distinct v from ContentVocabulary cv
              join cv.vocabularyEntry v
              join cv.contentItem ci
              join ci.section s
            where s.lesson.id = :lessonId
            order by v.id
            """)
    List<VocabularyEntry> findVocabularyByLessonId(@Param("lessonId") Long lessonId);

    @Query("""
            select cv from ContentVocabulary cv
              join fetch cv.vocabularyEntry v
              join cv.contentItem ci
              join ci.section s
            where s.lesson.id = :lessonId and cv.learningMeaningVi is not null
            order by ci.orderIndex, v.id
            """)
    List<ContentVocabulary> findLearningContextsByLessonId(@Param("lessonId") Long lessonId);

    /**
     * Các dòng nối một từ với một bài (một từ có thể nằm trong nhiều content_item của cùng bài).
     * Dùng khi gỡ từ khỏi bài: xoá ĐÚNG những dòng này, tuyệt đối không đụng vocabulary_entry.
     */
    @Query("""
            select cv from ContentVocabulary cv
              join cv.contentItem ci
              join ci.section s
            where s.lesson.id = :lessonId and cv.vocabularyEntry.id = :vocabularyId
            """)
    List<ContentVocabulary> findByLessonIdAndVocabularyId(@Param("lessonId") Long lessonId,
                                                          @Param("vocabularyId") Long vocabularyId);

    /** Id các từ đã gắn vào bài, để đánh dấu trong kết quả tìm kiếm. */
    @Query("""
            select cv.vocabularyEntry.id from ContentVocabulary cv
              join cv.contentItem ci
              join ci.section s
            where s.lesson.id = :lessonId
            """)
    List<Long> findVocabularyIdsByLessonId(@Param("lessonId") Long lessonId);
}
