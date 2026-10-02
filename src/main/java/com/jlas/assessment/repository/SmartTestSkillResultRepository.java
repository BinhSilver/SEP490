package com.jlas.assessment.repository;

import com.jlas.assessment.entity.SmartTestSkillResult;
import com.jlas.assessment.entity.SmartTestSkillResult.SmartTestSkillResultId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SmartTestSkillResultRepository
        extends JpaRepository<SmartTestSkillResult, SmartTestSkillResultId> {
    List<SmartTestSkillResult> findBySessionAttemptIdOrderByIdSkillAsc(Long attemptId);

    boolean existsByIdAttemptIdAndIdSkill(Long attemptId, String skill);
}
