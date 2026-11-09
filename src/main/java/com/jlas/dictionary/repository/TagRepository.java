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

    /**
     * Chủ đề của GIÁO TRÌNH có code bắt đầu bằng tiền tố này (tools/generate_seed.py: TOPIC-DAILY, TOPIC-TRAVEL…).
     * Tag SITUATION khác (vd. JLPT/BA/TRAVEL/DAILY_LIFE của Smart Test) dùng chung tag_type nhưng không thuộc Giáo trình.
     */
    String TEXTBOOK_TOPIC_CODE_PREFIX = "TOPIC-";

    List<Tag> findByTagTypeOrderByCodeAsc(String tagType);

    /** Chủ đề Giáo trình: tag loại {@code tagType} có code bắt đầu bằng {@code codePrefix}, xếp theo code. */
    List<Tag> findByTagTypeAndCodeStartingWithOrderByCodeAsc(String tagType, String codePrefix);

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
              and """ + TextbookRepository.LIBRARY_BOOK_CONDITION + """

            group by lt.tag.id
            """)
    List<Object[]> countLessonsByTagTypeAndStatus(@Param("tagType") String tagType, @Param("status") String status);

    /**
     * Cho CONTENT_CREATOR ở màn hình duyệt thường: số bài theo tag, trừ bản đã lưu trữ ở cả hai tầng.
     * Đối xứng với {@link #countLessonsByTagTypeAndStatus} nhưng loại trừ thay vì lọc bằng.
     */
    @Query("""
            select lt.tag.id, count(l.id) from LessonTag lt
              join lt.tag tg
              join lt.lesson l
              join l.unit u
              join u.textbook t
            where tg.tagType = :tagType and l.status <> :excludedStatus and t.status <> :excludedStatus
              and """ + TextbookRepository.LIBRARY_BOOK_CONDITION + """

            group by lt.tag.id
            """)
    List<Object[]> countLessonsByTagTypeAndStatusNot(@Param("tagType") String tagType,
                                                     @Param("excludedStatus") String excludedStatus);

    /** Tag của một loại, xếp theo tên để đổ dropdown ở màn hình quản lý nội dung. */
    List<Tag> findByTagTypeOrderByNameAsc(String tagType);

    /** tag.code có UNIQUE (uk_tag_code) nên kiểm tra trước khi tạo để trả 409 thay vì lỗi DB. */
    boolean existsByCode(String code);
}
