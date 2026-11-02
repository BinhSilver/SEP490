package com.jlas.jlptpath.repository;

import com.jlas.jlptpath.entity.JpathUserItem;
import com.jlas.jlptpath.enums.JpathItemState;
import com.jlas.jlptpath.enums.JpathItemType;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;

/** [JLAS-W0108] Learner state of items. */
@Repository
public interface JpathUserItemRepository extends JpaRepository<JpathUserItem, Long> {

    Optional<JpathUserItem> findByUserIdAndItemTypeAndItemCode(Long userId, JpathItemType itemType, String itemCode);

    /** Items of a level counted towards progress (countedAt set). */
    long countByUserIdAndLevelAndItemTypeAndCountedAtIsNotNull(Long userId, String level, JpathItemType itemType);

    /** Items of one type counted towards progress on any level (badges KNOWN_n). */
    long countByUserIdAndItemTypeAndCountedAtIsNotNull(Long userId, JpathItemType itemType);

    /** Items of a level counted before {@code before} (learner local time), e.g. before the start of today. */
    long countByUserIdAndLevelAndItemTypeAndCountedAtBefore(Long userId, String level, JpathItemType itemType,
                                                            LocalDateTime before);

    List<JpathUserItem> findByUserIdAndItemTypeAndItemCodeIn(Long userId, JpathItemType itemType,
                                                             Collection<String> itemCodes);

    List<JpathUserItem> findByUserIdAndItemType(Long userId, JpathItemType itemType);

    /** Codes of every item of {@code t} the learner has a row for (started, in the notebook or known). */
    @Query("select i.itemCode from JpathUserItem i where i.userId = :userId and i.itemType = :t")
    List<String> findCodesByUserIdAndItemType(@Param("userId") Long userId, @Param("t") JpathItemType t);

    /** Size of {@link #due}: items of {@code t} not KNOWN with next review at or before {@code localNow}. */
    @Query("""
            select count(i) from JpathUserItem i
            where i.userId = :userId and i.itemType = :t
              and i.state <> com.jlas.jlptpath.enums.JpathItemState.KNOWN
              and i.nextReviewAt <= :localNow
            """)
    long countDue(@Param("userId") Long userId, @Param("t") JpathItemType t,
                  @Param("localNow") LocalDateTime localNow);

    long countByUserIdAndItemTypeAndState(Long userId, JpathItemType itemType, JpathItemState state);

    /** Items of a level in one state (KNOWN / NOTEBOOK counts of a tab). */
    long countByUserIdAndLevelAndItemTypeAndState(Long userId, String level, JpathItemType itemType,
                                                  JpathItemState state);

    /**
     * Review queue of one item type: not KNOWN and due at {@code now} (learner local time), NOTEBOOK items first,
     * then the oldest due; id breaks ties so the order is stable.
     */
    @Query("""
            select i from JpathUserItem i
            where i.userId = :userId and i.itemType = :t
              and i.state <> com.jlas.jlptpath.enums.JpathItemState.KNOWN
              and i.nextReviewAt <= :now
            order by case when i.state = com.jlas.jlptpath.enums.JpathItemState.NOTEBOOK then 0 else 1 end,
                     i.nextReviewAt, i.id
            """)
    List<JpathUserItem> due(@Param("userId") Long userId, @Param("t") JpathItemType t,
                            @Param("now") LocalDateTime now, Pageable p);

    /**
     * Restart: bulk-deletes every item row of the learner and returns the row count (managed entities are not
     * detached).
     */
    @Modifying(flushAutomatically = true)
    @Query("delete from JpathUserItem i where i.userId = :userId")
    int deleteByUserId(@Param("userId") Long userId);
}
