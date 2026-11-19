package com.jlas.dictionary.repository;

import com.jlas.dictionary.entity.ContentVocabulary;
import com.jlas.dictionary.entity.VocabularyEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Repository cho {@link ContentVocabulary}. */
@Repository
public interface ContentVocabularyRepository extends JpaRepository<ContentVocabulary, ContentVocabulary.ContentVocabularyId> {

    /**
     * Toàn bộ từ vựng thuộc một lesson (đi qua content_item → lesson_section), 1 query, không trùng.
     * Thứ tự: display_order (bài đã gộp mục trùng — migration 088) rồi id từ vựng; bài chưa có display_order sắp theo
     * id như trước. Một từ gắn ở nhiều mục của bài lấy vị trí sớm nhất.
     * JOIN chỉ theo khoá số, không đụng cột normalized_* (collation utf8mb4_0900_as_cs).
     */
    default List<VocabularyEntry> findVocabularyByLessonId(Long lessonId) {
        Map<Long, VocabularyEntry> unique = new LinkedHashMap<>();
        for (ContentVocabulary link : findLinksByLessonIdOrdered(lessonId)) {
            unique.putIfAbsent(link.getVocabularyEntry().getId(), link.getVocabularyEntry());
        }
        return new ArrayList<>(unique.values());
    }

    @Query("""
            select cv from ContentVocabulary cv
              join fetch cv.vocabularyEntry v
              join cv.contentItem ci
              join ci.section s
            where s.lesson.id = :lessonId
            order by case when cv.displayOrder is null then 1 else 0 end, cv.displayOrder, v.id
            """)
    List<ContentVocabulary> findLinksByLessonIdOrdered(@Param("lessonId") Long lessonId);

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

    /**
     * Câu ví dụ của TỪ VỰNG theo bài: content_item GRAMMAR (câu ví dụ) nối với từ qua content_vocabulary.
     * Câu ví dụ ngữ pháp không bao giờ nối với từ, nên liên kết này là dấu hiệu phân biệt. Cột: content_item id, vocabulary id.
     */
    @Query("""
            select ci.id, cv.vocabularyEntry.id from ContentVocabulary cv
              join cv.contentItem ci
              join ci.section s
            where s.lesson.id = :lessonId and ci.contentType = 'GRAMMAR'
            """)
    List<Object[]> findVocabularyExampleLinksByLessonId(@Param("lessonId") Long lessonId);

    /** Id các từ đã gắn vào bài, để đánh dấu trong kết quả tìm kiếm. */
    @Query("""
            select cv.vocabularyEntry.id from ContentVocabulary cv
              join cv.contentItem ci
              join ci.section s
            where s.lesson.id = :lessonId
            """)
    List<Long> findVocabularyIdsByLessonId(@Param("lessonId") Long lessonId);
}
