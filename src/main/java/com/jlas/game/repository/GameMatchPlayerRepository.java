package com.jlas.game.repository;

import com.jlas.game.entity.GameMatchPlayer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/** Repository cho {@link GameMatchPlayer}. */
@Repository
public interface GameMatchPlayerRepository extends JpaRepository<GameMatchPlayer, Long> {
    List<GameMatchPlayer> findByMatchMatchId(Long matchId);
}
