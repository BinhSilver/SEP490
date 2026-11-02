package com.jlas.jlptpath.repository;

import com.jlas.jlptpath.entity.JpathConfusablePair;
import com.jlas.jlptpath.entity.JpathConfusablePairId;
import com.jlas.jlptpath.enums.JpathItemType;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** [JLAS-W0108] Pairs of easily confused items. */
@Repository
public interface JpathConfusablePairRepository extends JpaRepository<JpathConfusablePair, JpathConfusablePairId> {

    /** Every pair of {@code type} that contains {@code code} on either side, by the two codes. */
    @Query("""
            select p from JpathConfusablePair p
            where p.itemType = :type and (p.codeA = :code or p.codeB = :code)
            order by p.codeA, p.codeB
            """)
    List<JpathConfusablePair> findWithCode(@Param("type") JpathItemType type, @Param("code") String code);
}
