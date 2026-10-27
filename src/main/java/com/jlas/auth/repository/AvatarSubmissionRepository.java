package com.jlas.auth.repository;

import com.jlas.auth.entity.AvatarSubmission;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AvatarSubmissionRepository extends JpaRepository<AvatarSubmission, Long> {
    Optional<AvatarSubmission> findFirstByUserIdOrderByIdDesc(Long userId);
    boolean existsByUserIdAndStorageKeyAndStatus(Long userId, String storageKey, String status);
}
