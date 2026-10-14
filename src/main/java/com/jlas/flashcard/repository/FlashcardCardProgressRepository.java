package com.jlas.flashcard.repository;

import com.jlas.flashcard.entity.FlashcardCardProgress;
import com.jlas.flashcard.entity.FlashcardCardProgress.FlashcardCardProgressId;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FlashcardCardProgressRepository extends JpaRepository<FlashcardCardProgress, FlashcardCardProgressId> {
    List<FlashcardCardProgress> findByUserIdAndCardSetId(Long userId, Long setId);

    /*
     * Hàng ôn SRS gộp mọi bộ thẻ: chỉ thẻ đã học (khác NEW) trong bộ còn học được — bộ của mình hoặc bộ công khai,
     * chưa xoá, không phải bản nháp (cùng chính sách với FlashcardStudyAccess.studyable).
     */
    @Query("""
            select p from FlashcardCardProgress p join fetch p.card c join fetch c.set s
            where p.id.userId = :userId and p.status <> 'NEW' and p.nextReviewAt <= :until
              and s.deletedAt is null and s.draft = false
              and (s.owner.id = :userId or s.visibility = 'PUBLIC')
            order by p.nextReviewAt asc, p.id.cardId asc
            """)
    List<FlashcardCardProgress> findDue(@Param("userId") Long userId, @Param("until") LocalDateTime until, Pageable pageable);

    @Query("""
            select count(p) from FlashcardCardProgress p join p.card c join c.set s
            where p.id.userId = :userId and p.status <> 'NEW' and p.nextReviewAt <= :until
              and s.deletedAt is null and s.draft = false
              and (s.owner.id = :userId or s.visibility = 'PUBLIC')
            """)
    long countDue(@Param("userId") Long userId, @Param("until") LocalDateTime until);

    /** Thời điểm đến hạn của các thẻ sẽ tới hạn trong khoảng [from, until) — dùng cho dự báo vài ngày tới. */
    @Query("""
            select p.nextReviewAt from FlashcardCardProgress p join p.card c join c.set s
            where p.id.userId = :userId and p.status <> 'NEW' and p.nextReviewAt >= :from and p.nextReviewAt < :until
              and s.deletedAt is null and s.draft = false
              and (s.owner.id = :userId or s.visibility = 'PUBLIC')
            """)
    List<LocalDateTime> findUpcomingDueTimes(@Param("userId") Long userId, @Param("from") LocalDateTime from,
                                             @Param("until") LocalDateTime until);

    /** Số thẻ theo trạng thái (LEARNING/MASTERED) trong các bộ còn học được. */
    @Query("""
            select p.status, count(p) from FlashcardCardProgress p join p.card c join c.set s
            where p.id.userId = :userId and p.status <> 'NEW'
              and s.deletedAt is null and s.draft = false
              and (s.owner.id = :userId or s.visibility = 'PUBLIC')
            group by p.status
            """)
    List<Object[]> countByStatus(@Param("userId") Long userId);
}
