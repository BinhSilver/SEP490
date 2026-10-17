package com.jlas.dictionary.repository;

import com.jlas.dictionary.entity.ContentItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

/** Repository cho {@link ContentItem}. */
@Repository
public interface ContentItemRepository extends JpaRepository<ContentItem, Long> {

    /** Foundation player fetches the source lesson with each item to distinguish Kana from whole-word examples. */
    @Query("select ci from ContentItem ci join fetch ci.section s join fetch s.lesson where ci.id in :ids")
    List<ContentItem> findAllWithLessonByIdIn(@Param("ids") Collection<Long> ids);

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

    /**
     * [JLAS-W0108] P2 — câu ví dụ (GRAMMAR) gắn đúng một mẫu ngữ pháp VÀ nằm trong đúng bài nguồn
     * của mẫu đó (không lấy ví dụ cùng grammar_point_id nhưng thuộc bài khác). Dùng cho grammar
     * manifest: chỉ trả ví dụ đúng mẫu đã chọn, không kéo toàn bộ content_item của bài.
     */
    @Query("""
            select ci from ContentItem ci
              join ci.section s
            where ci.grammarPointId = :grammarPointId
              and ci.contentType = 'GRAMMAR'
              and s.lesson.id = :lessonId
            order by ci.orderIndex, ci.id
            """)
    List<ContentItem> findGrammarExamples(@Param("grammarPointId") Long grammarPointId,
                                          @Param("lessonId") Long lessonId);

    /**
     * Câu ví dụ đang gắn vào một mẫu ngữ pháp, kèm section. Dùng ở màn hình biên soạn và để chặn xoá
     * mẫu còn ví dụ: fk_content_item_grammar là ON DELETE SET NULL, xoá mẫu sẽ để lại câu ví dụ không
     * còn thuộc mẫu nào thay vì báo lỗi.
     */
    @Query("""
            select ci from ContentItem ci
              join fetch ci.section s
            where ci.grammarPointId = :grammarPointId
            order by s.orderIndex, ci.orderIndex, ci.id
            """)
    List<ContentItem> findByGrammarPointId(@Param("grammarPointId") Long grammarPointId);

    long countByGrammarPointId(Long grammarPointId);

    /**
     * Nguồn gốc của các content_item (bảng content_source, chỉ có ở dữ liệu nhập từ nguồn ngoài như vnjpclub):
     * entity_id, stable_code, source_url. Mục tự soạn không có dòng nào.
     */
    @Query(value = "select entity_id, stable_code, source_url from content_source "
            + "where entity_type = 'CONTENT_ITEM' and entity_id in (:ids)", nativeQuery = true)
    List<Object[]> findSourcesByContentItemIds(@Param("ids") Collection<Long> contentItemIds);

    /** Mục nội dung của một phần theo loại; dùng để tìm chỗ chứa sẵn có thay vì tạo trùng. */
    List<ContentItem> findBySectionIdAndContentType(Long sectionId, String contentType);

    /** Lời thoại của một đoạn theo thứ tự hiện tại; thứ tự ổn định khi order_index trùng nhau. */
    List<ContentItem> findBySectionIdAndContentTypeOrderByOrderIndexAscIdAsc(Long sectionId, String contentType);

    /** order_index lớn nhất trong một phần nội dung; null khi phần chưa có mục nào. Dùng để thêm vào cuối. */
    @Query("select max(ci.orderIndex) from ContentItem ci where ci.section.id = :sectionId")
    Integer findMaxOrderIndexBySectionId(@Param("sectionId") Long sectionId);
}
