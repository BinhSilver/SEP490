package com.jlas.dictionary.repository;

import com.jlas.dictionary.entity.Unit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/** Repository cho {@link Unit}. */
@Repository
public interface UnitRepository extends JpaRepository<Unit, Long> {

    List<Unit> findByTextbookIdOrderByOrderIndexAscIdAsc(Long textbookId);
}
