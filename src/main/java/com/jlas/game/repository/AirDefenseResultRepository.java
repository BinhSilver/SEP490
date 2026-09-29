package com.jlas.game.repository;

import com.jlas.game.entity.AirDefenseResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Repository cho {@link AirDefenseResult}. */
@Repository
public interface AirDefenseResultRepository extends JpaRepository<AirDefenseResult, Long> {
}
