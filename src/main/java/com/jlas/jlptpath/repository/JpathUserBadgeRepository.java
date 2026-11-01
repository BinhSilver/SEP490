package com.jlas.jlptpath.repository;

import com.jlas.jlptpath.entity.JpathUserBadge;
import com.jlas.jlptpath.entity.JpathUserBadgeId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** [JLAS-W0108] Badges earned by learners. */
@Repository
public interface JpathUserBadgeRepository extends JpaRepository<JpathUserBadge, JpathUserBadgeId> {
}
