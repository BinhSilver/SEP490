package com.jlas.game.repository;

import com.jlas.game.entity.UserSpaceship;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

/** Repository cho {@link UserSpaceship}. */
@Repository
public interface UserSpaceshipRepository extends JpaRepository<UserSpaceship, Long> {
    List<UserSpaceship> findByUserId(Long userId);
}
