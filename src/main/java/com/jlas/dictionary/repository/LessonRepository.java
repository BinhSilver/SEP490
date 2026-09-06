package com.jlas.dictionary.repository;

import com.jlas.dictionary.entity.Lesson;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Repository cho {@link Lesson}. */
@Repository
public interface LessonRepository extends JpaRepository<Lesson, Long> { }
