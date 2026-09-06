package com.jlas.mission.repository;

import com.jlas.mission.entity.UserStreaks;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

/** Repository cho {@link UserStreaks}. */
@Repository
public interface UserStreaksRepository extends JpaRepository<UserStreaks, Long> {
    Optional<UserStreaks> findByUserId(Long userId);
}
