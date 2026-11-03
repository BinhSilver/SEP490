package com.jlas.jlptpath.repository;

import com.jlas.jlptpath.entity.JpathSkillSession;
import com.jlas.jlptpath.enums.JpathSkill;
import com.jlas.jlptpath.enums.JpathSkillSessionKind;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** [JLAS-W0108] Skill sessions. */
@Repository
public interface JpathSkillSessionRepository extends JpaRepository<JpathSkillSession, Long> {

    /** The open session of (plan, skill, kind), if any (uk_jpath_skill_open allows one). */
    Optional<JpathSkillSession> findFirstByUserIdAndPlanIdAndSkillAndKindAndCompletedAtIsNullOrderByIdDesc(
            Long userId, Long planId, JpathSkill skill, JpathSkillSessionKind kind);

    /** The latest completed session of (plan, skill, kind), if any. */
    Optional<JpathSkillSession> findFirstByUserIdAndPlanIdAndSkillAndKindAndCompletedAtIsNotNullOrderByIdDesc(
            Long userId, Long planId, JpathSkill skill, JpathSkillSessionKind kind);

    /** Completed sessions of one kind with {@code from <= study_day <= to}. */
    long countByUserIdAndKindAndStudyDayBetweenAndCompletedAtIsNotNull(Long userId, JpathSkillSessionKind kind,
            LocalDate from, LocalDate to);

    /** The session when it is the learner's (another learner's id reads as absent). */
    Optional<JpathSkillSession> findByIdAndUserId(Long id, Long userId);

    /** The learner's open sessions of one type, newest first. */
    List<JpathSkillSession> findByUserIdAndTypeCodeAndCompletedAtIsNullOrderByIdDesc(Long userId, String typeCode);

    /** Whether the learner passed the lesson check of this type in any session. */
    boolean existsByUserIdAndTypeCodeAndLessonPassedAtIsNotNull(Long userId, String typeCode);
}
