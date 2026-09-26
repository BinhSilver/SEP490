package com.jlas.learning.service.impl;

import com.jlas.assessment.entity.AssessmentAnswer;
import com.jlas.assessment.entity.AssessmentAttempt;
import com.jlas.assessment.entity.AssessmentQuestion;
import com.jlas.assessment.entity.SmartTestSession;
import com.jlas.assessment.entity.UserLearningSurvey;
import com.jlas.assessment.repository.AssessmentAnswerRepository;
import com.jlas.assessment.repository.AssessmentAttemptRepository;
import com.jlas.assessment.repository.SmartTestSessionRepository;
import com.jlas.assessment.repository.UserLearningSurveyRepository;
import com.jlas.assessment.service.impl.SmartTestQuestionSelector;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.dictionary.entity.GrammarPoint;
import com.jlas.dictionary.entity.Outcome;
import com.jlas.dictionary.entity.Tag;
import com.jlas.dictionary.entity.VocabularyEntry;
import com.jlas.learning.dto.LearningProfileSnapshot;
import com.jlas.learning.dto.WeaknessEvidence;
import com.jlas.learning.service.LearningProfileSource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Production adapter: builds a {@link LearningProfileSnapshot} from a real, finalized Smart Test
 * attempt. Ownership and SUBMITTED status are verified server-side.
 *
 * <p>[JLAS-W0108] Evidence điểm yếu được gom đủ 5 skill theo canonical ID (spec §2.1):
 * KANJI/VOCAB gom theo lexeme; GRAMMAR gom theo grammarPointId (tag lesson là phân loại bổ sung);
 * READING/LISTENING gom cấp skill với outcome/topic/situation yếu.</p>
 */
@Component
@Primary
@RequiredArgsConstructor
public class SmartTestLearningProfileSource implements LearningProfileSource {
    private static final String ASSESSMENT_REVISION = "smart-test-v1";

    private final AssessmentAttemptRepository attempts;
    private final SmartTestSessionRepository sessions;
    private final UserLearningSurveyRepository surveys;
    private final AssessmentAnswerRepository answers;

    @Override
    @Transactional(readOnly = true)
    public LearningProfileSnapshot loadForUser(Long userId, Long assessmentAttemptId) {
        AssessmentAttempt attempt = attempts.findByIdAndUserId(assessmentAttemptId, userId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Assessment attempt not found."));
        if (!"SUBMITTED".equals(attempt.getStatus())) {
            throw new ApiException(ErrorCode.CONFLICT, "Smart Test results are not finalized yet.");
        }
        SmartTestSession session = sessions.findById(assessmentAttemptId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Smart Test session not found."));
        UserLearningSurvey survey = surveys.findById(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Learning survey not found."));

        return new LearningProfileSnapshot(userId, assessmentAttemptId, survey.getUserId(),
                ASSESSMENT_REVISION, survey.getCompletedAt() != null, true,
                assessedLevel(session), targetLevel(session, survey), session.getGoal(),
                session.getDailyStudyMinutes() != null ? session.getDailyStudyMinutes() : survey.getDailyStudyMinutes(),
                aggregateWeaknesses(assessmentAttemptId));
    }

    private static String assessedLevel(SmartTestSession session) {
        return session.getEstimatedOverallLevel() != null
                ? session.getEstimatedOverallLevel()
                : session.getSelfReportedLevel();
    }

    private static String targetLevel(SmartTestSession session, UserLearningSurvey survey) {
        return session.getTargetLevel() != null ? session.getTargetLevel() : survey.getTargetLevel();
    }

    /** Gom evidence theo 5 skill; chỉ item/điểm thực sự sai mới trở thành điểm yếu. */
    private List<WeaknessEvidence> aggregateWeaknesses(Long attemptId) {
        Map<String, List<AssessmentAnswer>> bySkill = new HashMap<>();
        for (AssessmentAnswer answer : answers.findWithQuestionContextByAttemptId(attemptId)) {
            bySkill.computeIfAbsent(answer.getQuestion().getSkill(), key -> new ArrayList<>()).add(answer);
        }
        List<WeaknessEvidence> result = new ArrayList<>();
        result.addAll(itemEvidence(bySkill.get(WeaknessEvidence.SKILL_KANJI), WeaknessEvidence.SKILL_KANJI));
        result.addAll(itemEvidence(bySkill.get(WeaknessEvidence.SKILL_VOCABULARY), WeaknessEvidence.SKILL_VOCABULARY));
        result.addAll(grammarEvidence(bySkill.get(WeaknessEvidence.SKILL_GRAMMAR)));
        result.addAll(comprehensionEvidence(bySkill.get(WeaknessEvidence.SKILL_READING), WeaknessEvidence.SKILL_READING));
        result.addAll(comprehensionEvidence(bySkill.get(WeaknessEvidence.SKILL_LISTENING), WeaknessEvidence.SKILL_LISTENING));
        return result;
    }

    /** KANJI/VOCAB: câu nào sai thì ghi chính lexeme đó (không gom cả bài). */
    private List<WeaknessEvidence> itemEvidence(List<AssessmentAnswer> answers, String skill) {
        if (answers == null || answers.isEmpty()) return List.of();
        Map<String, int[]> counts = new LinkedHashMap<>();
        Map<String, String> levels = new LinkedHashMap<>();
        Map<String, Long> vocabIds = new LinkedHashMap<>();
        for (AssessmentAnswer answer : answers) {
            String lexeme = SmartTestQuestionSelector.lexeme(answer.getQuestion());
            if (lexeme == null) continue;
            String key = skill + ":" + lexeme;
            int[] count = counts.computeIfAbsent(key, k -> new int[2]);
            count[0]++;
            if (isWrong(answer)) count[1]++;
            levels.putIfAbsent(key, answer.getQuestion().getJlptLevel());
            VocabularyEntry entry = answer.getQuestion().getVocabularyEntry();
            if (entry != null) vocabIds.putIfAbsent(key, entry.getId());
        }
        return counts.entrySet().stream()
                .filter(entry -> entry.getValue()[1] >= 1)
                .map(entry -> WeaknessEvidence.item(skill, entry.getKey(),
                        entry.getValue()[0], entry.getValue()[1], levels.get(entry.getKey()),
                        vocabIds.get(entry.getKey())))
                .toList();
    }

    /** GRAMMAR: tổng hợp theo grammarPointId; tag lesson là phân loại bổ sung. */
    private List<WeaknessEvidence> grammarEvidence(List<AssessmentAnswer> answers) {
        if (answers == null || answers.isEmpty()) return List.of();
        Map<Long, int[]> counts = new LinkedHashMap<>();
        Map<Long, GrammarPoint> points = new HashMap<>();
        Map<Long, String> levels = new HashMap<>();
        for (AssessmentAnswer answer : answers) {
            GrammarPoint point = answer.getQuestion().getGrammarPoint();
            if (point == null) continue; // chưa gắn grammar_point_id (ambiguous / ngoài chương trình)
            points.putIfAbsent(point.getId(), point);
            levels.putIfAbsent(point.getId(), answer.getQuestion().getJlptLevel());
            int[] count = counts.computeIfAbsent(point.getId(), key -> new int[2]);
            count[0]++;
            if (isWrong(answer)) count[1]++;
        }
        List<WeaknessEvidence> result = new ArrayList<>();
        for (var entry : counts.entrySet()) {
            GrammarPoint point = points.get(entry.getKey());
            Tag lessonTag = point.getLesson() == null ? null : point.getLesson().getKnowledgeTag();
            result.add(WeaknessEvidence.grammar(entry.getKey(),
                    lessonTag == null ? null : lessonTag.getCode(),
                    lessonTag == null ? null : lessonTag.getId(),
                    entry.getValue()[0], entry.getValue()[1], levels.get(entry.getKey())));
        }
        return result;
    }

    /** READING/LISTENING: cửa sổ 5 câu cấp skill; ghi outcome/topic/situation/level yếu (chiếm đa số câu sai). */
    private List<WeaknessEvidence> comprehensionEvidence(List<AssessmentAnswer> answers, String skill) {
        if (answers == null || answers.isEmpty()) return List.of();
        int assessed = 0;
        int wrong = 0;
        Map<String, Integer> wrongByOutcome = new HashMap<>();
        Map<String, Long> outcomeIdByCode = new HashMap<>();
        Map<String, Integer> wrongByTopic = new HashMap<>();
        Map<String, Long> topicIdByCode = new HashMap<>();
        Map<String, Integer> wrongBySituation = new HashMap<>();
        Map<String, Long> situationIdByCode = new HashMap<>();
        Map<String, Integer> wrongByLevel = new HashMap<>();

        for (AssessmentAnswer answer : answers) {
            AssessmentQuestion question = answer.getQuestion();
            assessed++;
            if (!isWrong(answer)) continue;
            wrong++;
            Outcome outcome = question.getOutcome();
            if (outcome != null) {
                wrongByOutcome.merge(outcome.getCode(), 1, Integer::sum);
                outcomeIdByCode.putIfAbsent(outcome.getCode(), outcome.getId());
            }
            Tag topic = question.getTopicTag();
            if (topic != null) {
                wrongByTopic.merge(topic.getCode(), 1, Integer::sum);
                topicIdByCode.putIfAbsent(topic.getCode(), topic.getId());
            }
            Tag situation = question.getKnowledgeTag();
            if (situation != null) {
                wrongBySituation.merge(situation.getCode(), 1, Integer::sum);
                situationIdByCode.putIfAbsent(situation.getCode(), situation.getId());
            }
            if (question.getJlptLevel() != null) {
                wrongByLevel.merge(question.getJlptLevel(), 1, Integer::sum);
            }
        }

        String outcomeCode = dominant(wrongByOutcome);
        String topicCode = dominant(wrongByTopic);
        String situationCode = dominant(wrongBySituation);
        return List.of(WeaknessEvidence.comprehension(skill, assessed, wrong,
                situationCode, situationIdByCode.get(situationCode),
                outcomeCode, outcomeIdByCode.get(outcomeCode),
                topicCode, topicIdByCode.get(topicCode),
                dominant(wrongByLevel)));
    }

    /** Phần tử có số lần sai lớn nhất; hoà thì chọn key nhỏ hơn để kết quả deterministic. */
    private static String dominant(Map<String, Integer> counts) {
        if (counts.isEmpty()) return null;
        return counts.entrySet().stream()
                .max((first, second) -> {
                    int byCount = Integer.compare(first.getValue(), second.getValue());
                    return byCount != 0 ? byCount : second.getKey().compareTo(first.getKey());
                })
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    /** NO_RESPONSE (hết giờ, selectedOption null) được tính là SAI (spec §2.1). */
    private static boolean isWrong(AssessmentAnswer answer) {
        return answer.getSelectedOption() == null || Boolean.FALSE.equals(answer.getIsCorrect());
    }
}
