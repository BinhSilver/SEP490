package com.jlas.dictionary.repository;

import com.jlas.dictionary.entity.Unit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** Repository cho {@link Unit}. */
@Repository
public interface UnitRepository extends JpaRepository<Unit, Long> {

    List<Unit> findByTextbookIdOrderByOrderIndexAscIdAsc(Long textbookId);

    Optional<Unit> findByTextbookIdAndSourceCode(Long textbookId, String sourceCode);
}
