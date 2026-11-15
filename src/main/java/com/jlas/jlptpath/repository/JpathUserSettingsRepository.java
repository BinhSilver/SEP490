package com.jlas.jlptpath.repository;

import com.jlas.jlptpath.entity.JpathUserSettings;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** [JLAS-W0108] Display settings of learners. */
@Repository
public interface JpathUserSettingsRepository extends JpaRepository<JpathUserSettings, Long> {

    /** [JLAS-W0108] F126: the row locked for the transaction, so only one call at a time makes the path notebook. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from JpathUserSettings s where s.userId = :userId")
    Optional<JpathUserSettings> lockById(@Param("userId") Long userId);
}
