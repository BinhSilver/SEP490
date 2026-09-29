package com.jlas.dictionary.repository;

import com.jlas.dictionary.entity.GrammarPoint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/** Repository cho {@link GrammarPoint}. */
@Repository
public interface GrammarPointRepository extends JpaRepository<GrammarPoint, Long> {

    List<GrammarPoint> findByLessonIdOrderByOrderIndexAsc(Long lessonId);
}
