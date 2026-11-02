package com.jlas.jlptpath.repository;

import com.jlas.jlptpath.entity.JpathKanaRow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** [JLAS-W0108] Kana rows. */
@Repository
public interface JpathKanaRowRepository extends JpaRepository<JpathKanaRow, String> {

    /** Rows that have kana and whose every kana has a KANA user item of the learner with counted_at set. */
    @Query("""
            select count(r) from JpathKanaRow r
            where exists (select k from JpathKana k where k.rowCode = r.code)
              and not exists (
                select k from JpathKana k
                where k.rowCode = r.code
                  and not exists (
                    select i from JpathUserItem i
                    where i.userId = :userId and i.itemType = com.jlas.jlptpath.enums.JpathItemType.KANA
                      and i.itemCode = k.code and i.countedAt is not null))
            """)
    long countCompletedRows(@Param("userId") Long userId);
}
