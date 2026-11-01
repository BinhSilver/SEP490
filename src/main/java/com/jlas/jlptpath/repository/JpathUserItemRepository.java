package com.jlas.jlptpath.repository;

import com.jlas.jlptpath.entity.JpathUserItem;
import com.jlas.jlptpath.enums.JpathItemState;
import com.jlas.jlptpath.enums.JpathItemType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** [JLAS-W0108] Learner state of items. */
@Repository
public interface JpathUserItemRepository extends JpaRepository<JpathUserItem, Long> {

    Optional<JpathUserItem> findByUserIdAndItemTypeAndItemCode(Long userId, JpathItemType itemType, String itemCode);

    /** Items of a level counted towards progress (countedAt set). */
    long countByUserIdAndLevelAndItemTypeAndCountedAtIsNotNull(Long userId, String level, JpathItemType itemType);

    long countByUserIdAndItemTypeAndState(Long userId, JpathItemType itemType, JpathItemState state);

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
}
