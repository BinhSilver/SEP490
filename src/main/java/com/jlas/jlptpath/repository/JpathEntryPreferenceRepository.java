package com.jlas.jlptpath.repository;

import com.jlas.jlptpath.entity.JpathEntryPreference;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** [JLAS-W0108] Entry answers of learners. */
@Repository
public interface JpathEntryPreferenceRepository extends JpaRepository<JpathEntryPreference, Long> {
}
