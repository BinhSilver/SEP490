package com.jlas.game.repository;

import com.jlas.game.entity.GameMatch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Repository cho {@link GameMatch}. */
@Repository
public interface GameMatchRepository extends JpaRepository<GameMatch, Long> { }
