package com.jlas.assessment.repository;

import com.jlas.assessment.entity.AssessmentAnswer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface AssessmentAnswerRepository extends JpaRepository<AssessmentAnswer, Long> {
    boolean existsByAttemptIdAndQuestionId(Long attemptId, Long questionId);

    List<AssessmentAnswer> findByAttemptId(Long attemptId);

    /** [JLAS-W0108] Toàn bộ đáp án của attempt với ngữ cảnh câu hỏi (grammarPoint/lesson/tag/topic/outcome)
     *  để gom evidence điểm yếu theo 5 skill. Các khóa bổ sung (topic/outcome) chỉ có với Reading/Listening. */
    @Query("""
            select a from AssessmentAnswer a
            join fetch a.question q
            left join fetch q.grammarPoint gp
            left join fetch gp.lesson l
            left join fetch l.knowledgeTag t
            left join fetch q.topicTag tt
            left join fetch q.outcome o
            left join fetch q.vocabularyEntry ve
            where a.attempt.id = :attemptId
            """)
    List<AssessmentAnswer> findWithQuestionContextByAttemptId(@Param("attemptId") Long attemptId);
}
