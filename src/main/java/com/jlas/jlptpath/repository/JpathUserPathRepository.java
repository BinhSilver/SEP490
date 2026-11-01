package com.jlas.jlptpath.repository;

import com.jlas.jlptpath.entity.JpathUserPath;
import com.jlas.jlptpath.enums.JpathPathStatus;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** [JLAS-W0108] Learner paths. */
@Repository
public interface JpathUserPathRepository extends JpaRepository<JpathUserPath, Long> {

    boolean existsByUserIdAndStatus(Long userId, JpathPathStatus status);

    /** Newest path of the user in {@code status}. */
    Optional<JpathUserPath> findFirstByUserIdAndStatusOrderByIdDesc(Long userId, JpathPathStatus status);
}
