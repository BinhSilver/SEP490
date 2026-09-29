package com.jlas.game.repository;

import com.jlas.game.entity.MemoryMatchResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Repository cho {@link MemoryMatchResult}. */
@Repository
public interface MemoryMatchResultRepository extends JpaRepository<MemoryMatchResult, Long> {
}
