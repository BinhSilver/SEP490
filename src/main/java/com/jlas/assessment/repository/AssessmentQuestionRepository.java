package com.jlas.assessment.repository;

import com.jlas.assessment.entity.AssessmentQuestion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AssessmentQuestionRepository extends JpaRepository<AssessmentQuestion, Long> {
    Optional<AssessmentQuestion> findBySourceCode(String sourceCode);

    List<AssessmentQuestion> findByIsActiveTrueAndSkillAndJlptLevel(String skill, String jlptLevel);

    /** [JLAS-W0108] Câu reading/listening ở một cấp độ, thứ tự ổn định cho gate quiz. */
    List<AssessmentQuestion> findByIsActiveTrueAndSkillAndJlptLevelOrderByIdAsc(String skill, String jlptLevel);

    List<AssessmentQuestion> findByIsActiveTrueAndSkillAndJlptLevelAndSourceCodeStartingWithOrderByIdAsc(
            String skill, String jlptLevel, String sourceCodePrefix);

    List<AssessmentQuestion> findByIsActiveTrueAndSkillOrderByIdAsc(String skill);

    /** [JLAS-W0113] Câu hỏi cho lesson quiz: gom theo knowledge tag của bài học. */
    List<AssessmentQuestion> findByIsActiveTrueAndKnowledgeTagCodeOrderByIdAsc(String tagCode);

    /** [JLAS-W0113] Câu hỏi cho gate quiz (tổng kết warm-up): gom theo nhiều knowledge tag. */
    List<AssessmentQuestion> findByIsActiveTrueAndKnowledgeTagCodeInOrderByIdAsc(Collection<String> tagCodes);

    /** [JLAS-W0108] Câu KANJI/VOCAB gắn trực tiếp các từ đã warm-up (gate quiz đủ kĩ năng). */
    List<AssessmentQuestion> findByIsActiveTrueAndVocabularyEntryIdInOrderByIdAsc(Collection<Long> vocabularyEntryIds);

    /** [JLAS-W0108] Câu GRAMMAR gắn trực tiếp các mẫu ngữ pháp đã warm-up (gate quiz đủ kĩ năng). */
    List<AssessmentQuestion> findByIsActiveTrueAndGrammarPointIdInOrderByIdAsc(Collection<Long> grammarPointIds);
}
