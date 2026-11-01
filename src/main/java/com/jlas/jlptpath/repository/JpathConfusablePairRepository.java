package com.jlas.jlptpath.repository;

import com.jlas.jlptpath.entity.JpathConfusablePair;
import com.jlas.jlptpath.entity.JpathConfusablePairId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** [JLAS-W0108] Pairs of easily confused items. */
@Repository
public interface JpathConfusablePairRepository extends JpaRepository<JpathConfusablePair, JpathConfusablePairId> {
}
