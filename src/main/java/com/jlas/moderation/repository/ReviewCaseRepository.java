package com.jlas.moderation.repository;

import com.jlas.moderation.entity.ReviewCase;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Repository cho {@link ReviewCase}. */
@Repository
public interface ReviewCaseRepository extends JpaRepository<ReviewCase, Long> { }
