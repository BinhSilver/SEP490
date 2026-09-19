package com.jlas.assessment.service.impl;

import com.jlas.assessment.dto.SmartTestAnswerRequest;
import com.jlas.assessment.dto.SmartTestResponse;
import com.jlas.assessment.dto.SmartTestResponse.BatchResultView;
import com.jlas.assessment.dto.SmartTestResponse.GrammarPointResultView;
import com.jlas.assessment.dto.SmartTestResponse.QuestionView;
import com.jlas.assessment.dto.SmartTestResponse.ResultView;
import com.jlas.assessment.dto.SmartTestResponse.SkillResultView;
import com.jlas.assessment.dto.SmartTestStartRequest;
import com.jlas.assessment.entity.AssessmentAnswer;
import com.jlas.assessment.entity.AssessmentAttempt;
import com.jlas.assessment.entity.AssessmentQuestion;
import com.jlas.assessment.entity.SmartTestSession;
import com.jlas.assessment.entity.SmartTestSessionQuestion;
import com.jlas.assessment.entity.SmartTestSkillResult;
import com.jlas.assessment.repository.AssessmentAnswerRepository;
import com.jlas.assessment.repository.AssessmentAttemptRepository;
import com.jlas.assessment.repository.AssessmentQuestionRepository;
import com.jlas.assessment.repository.SmartTestSessionQuestionRepository;
import com.jlas.assessment.repository.SmartTestSessionRepository;
import com.jlas.assessment.repository.SmartTestSkillResultRepository;
import com.jlas.assessment.service.AssessmentService;
import com.jlas.assessment.service.impl.SmartTestAdaptivePolicy.BatchScore;
import com.jlas.assessment.service.impl.SmartTestAdaptivePolicy.Decision;
import com.jlas.dictionary.entity.GrammarPoint;
import com.jlas.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Cài đặt nghiệp vụ kiểm tra và đánh giá trình độ. */
@Service
@RequiredArgsConstructor
@Transactional
public class AssessmentServiceImpl implements AssessmentService {

    private static final Set<String> GOALS = Set.of(
            "JLPT", "BA", "TRAVEL", "DAILY_LIFE");
    /** Mục tiêu thiên về sử dụng thực tế (tình huống) thay vì thi cử kiến thức. */
    private static final Set<String> USAGE_GOALS = Set.of("TRAVEL", "DAILY_LIFE");
    private static final Map<String, List<String>> GOAL_TAG_PRIORITY = Map.of(
            "JLPT", List.of("JLPT"), "BA", List.of("BA"),
            "TRAVEL", List.of("TRAVEL", "DAILY_LIFE"), "DAILY_LIFE", List.of("DAILY_LIFE"));
    private static final List<String> SKILL_ORDER = List.of(
            "KANJI", "VOCABULARY", "GRAMMAR", "READING", "LISTENING");
    private static final Set<String> CURRENT_LEVELS = Set.of(
            "FROM_ZERO", "UNKNOWN", "N5", "N4", "N3", "N2", "N1");

    private final UserRepository userRepository;
    private final AssessmentAttemptRepository attemptRepository;
    private final AssessmentQuestionRepository questionRepository;
    private final AssessmentAnswerRepository answerRepository;
    private final SmartTestSessionRepository sessionRepository;
    private final SmartTestSessionQuestionRepository sessionQuestionRepository;
    private final SmartTestSkillResultRepository skillResultRepository;
    private final com.jlas.assessment.repository.UserLearningSurveyRepository surveyRepository;
    private final SmartTestAdaptivePolicy adaptivePolicy = new SmartTestAdaptivePolicy();

    @Override
    @Transactional(readOnly = true)
    public com.jlas.assessment.dto.LearningSurveyResponse getLearningSurvey(Long userId) {
        return surveyRepository.findById(userId).map(value ->
                new com.jlas.assessment.dto.LearningSurveyResponse(true, new SmartTestStartRequest(
                        value.getGoal(), value.getTargetLevel(), value.getSelfReportedLevel(),
                        value.getStudyDurationMonths(), value.getDailyStudyMinutes())))
                .orElseGet(() -> new com.jlas.assessment.dto.LearningSurveyResponse(false, null));
    }

    @Override
    public com.jlas.assessment.dto.LearningSurveyResponse saveLearningSurvey(Long userId, SmartTestStartRequest request) {
        String goal = allowed(request.goal(), GOALS, "Mục tiêu không hợp lệ.");
        String target = request.targetLevel() == null || request.targetLevel().isBlank() ? null
                : allowed(request.targetLevel(), Set.copyOf(SmartTestAdaptivePolicy.LEVELS), "Level mục tiêu không hợp lệ.");
        if ("JLPT".equals(goal) && target == null) throw badRequest("Vui lòng chọn cấp độ JLPT mục tiêu.");
        String self = allowed(request.selfReportedLevel(), CURRENT_LEVELS,
                "Level tự đánh giá không hợp lệ.");
        com.jlas.assessment.entity.UserLearningSurvey survey = surveyRepository.findById(userId)
                .orElseGet(() -> com.jlas.assessment.entity.UserLearningSurvey.builder()
                        .user(userRepository.findById(userId).orElseThrow(() ->
                                new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy người dùng."))).build());
        survey.setGoal(goal);
        survey.setTargetLevel("JLPT".equals(goal) ? target : null);
        survey.setSelfReportedLevel(self);
        survey.setStudyDurationMonths(request.studyDurationMonths());
        survey.setDailyStudyMinutes(request.dailyStudyMinutes());
        survey.setSkills(String.join(",", SKILL_ORDER));
        survey.setCompletedAt(LocalDateTime.now());
        surveyRepository.saveAndFlush(survey);
        if ("JLPT".equals(goal)) {
            com.jlas.auth.entity.User user = survey.getUser();
            user.setJapaneseLevel(target);
            userRepository.save(user);
        }
        return new com.jlas.assessment.dto.LearningSurveyResponse(true, new SmartTestStartRequest(
                goal, survey.getTargetLevel(), self, request.studyDurationMonths(), request.dailyStudyMinutes()));
    }

    @Override
    public SmartTestResponse startSmartTest(Long userId, SmartTestStartRequest request) {
        String goal = allowed(request.goal(), GOALS, "Mục tiêu không hợp lệ.");
        String self = allowed(request.selfReportedLevel(), CURRENT_LEVELS, "Level tự đánh giá không hợp lệ.");
        if ("FROM_ZERO".equals(self)) {
            throw badRequest("Bạn chưa từng học tiếng Nhật nên không cần làm Smart Test.");
        }
        if (USAGE_GOALS.contains(goal)
                && (request.studyDurationMonths() == null || request.studyDurationMonths() <= 6)) {
            throw badRequest("Với mục tiêu " + goal + ", bạn cần học tiếng Nhật trên 6 tháng mới cần làm Smart Test.");
        }
        String selfLevel = normalizeLevel(self);
        String targetLevel = request.targetLevel() == null || request.targetLevel().isBlank()
                ? null : allowed(request.targetLevel(), Set.copyOf(SmartTestAdaptivePolicy.LEVELS),
                "Mục tiêu JLPT không hợp lệ.");
        if ("JLPT".equals(goal) && targetLevel == null) {
            throw badRequest("Vui lòng chọn cấp độ JLPT mục tiêu.");
        }

        AssessmentAttempt attempt = attemptRepository.save(AssessmentAttempt.builder()
                .user(userRepository.findById(userId)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                                "Không tìm thấy người dùng.")))
                .attemptType("SMART_TEST")
                .status("IN_PROGRESS")
                .build());
        SmartTestSession session = sessionRepository.save(SmartTestSession.builder()
                .attempt(attempt)
                .goal(goal)
                .targetLevel(targetLevel)
                .selfReportedLevel(selfLevel)
                .studyDurationMonths(request.studyDurationMonths())
                .dailyStudyMinutes(request.dailyStudyMinutes())
                .build());

        List<SmartTestSessionQuestion> questions = new ArrayList<>();
        for (String skill : SKILL_ORDER) appendBatch(session, skill, selfLevel, 1, questions);
        return buildResponse(session, questions, List.of());
    }

    @Override
    @Transactional(readOnly = true)
    public SmartTestResponse getSmartTest(Long userId, Long attemptId) {
        SmartTestSession session = ownedSession(userId, attemptId);
        return buildResponse(session, sessionQuestionRepository.findAllForAttempt(attemptId),
                answerRepository.findByAttemptId(attemptId));
    }

    @Override
    public SmartTestResponse answerSmartTest(Long userId, Long attemptId, SmartTestAnswerRequest request) {
        SmartTestSession session = ownedSession(userId, attemptId);
        if (!"IN_PROGRESS".equals(session.getAttempt().getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Bài Smart Test này đã hoàn thành.");
        }

        List<SmartTestSessionQuestion> questions = new ArrayList<>(
                sessionQuestionRepository.findAllForAttempt(attemptId));
        List<SmartTestSkillResult> completedBeforeAnswer = skillResultRepository
                .findBySessionAttemptIdOrderByIdSkillAsc(attemptId);
        String activeSkill = activeSkill(questions, completedBeforeAnswer);
        SmartTestSessionQuestion answeredItem = questions.stream()
                .filter(item -> item.getQuestion().getId().equals(request.questionId()))
                .findFirst()
                .orElseThrow(() -> badRequest("Câu hỏi không thuộc bài Smart Test này."));
        if (!answeredItem.getQuestion().getSkill().equals(activeSkill)) {
            throw badRequest("Hãy hoàn thành kỹ năng " + activeSkill + " trước.");
        }
        if (answerRepository.existsByAttemptIdAndQuestionId(attemptId, request.questionId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Câu hỏi này đã được trả lời.");
        }

        AssessmentQuestion question = answeredItem.getQuestion();
        answerRepository.saveAndFlush(AssessmentAnswer.builder()
                .attempt(session.getAttempt())
                .question(question)
                .selectedOption(request.selectedOption())
                .isCorrect(request.selectedOption().equals(question.getCorrectOption()))
                .answeredAt(LocalDateTime.now())
                .build());

        List<AssessmentAnswer> answers = answerRepository.findByAttemptId(attemptId);
        advanceSkillIfBatchComplete(session, answeredItem, questions, answers);

        List<SmartTestSkillResult> completedSkills = skillResultRepository
                .findBySessionAttemptIdOrderByIdSkillAsc(attemptId);
        int skillCount = selectedSkills(questions).size();
        if (completedSkills.size() == skillCount) {
            finishAttempt(session, questions, answers, completedSkills);
        } else if (completedSkills.size() > completedBeforeAnswer.size()) {
            String nextSkill = activeSkill(questions, completedSkills);
            String inheritedLevel = completedSkills.stream()
                    .filter(result -> result.getId().getSkill().equals(activeSkill))
                    .findFirst()
                    .map(SmartTestSkillResult::getEstimatedLevel)
                    .orElseThrow();
            initializeNextSkill(session, nextSkill, inheritedLevel, questions);
        }
        return buildResponse(session, questions, answers);
    }

    @Override
    public SmartTestResponse finishSmartTest(Long userId, Long attemptId) {
        SmartTestSession session = ownedSession(userId, attemptId);
        if (!"IN_PROGRESS".equals(session.getAttempt().getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Bài Smart Test này đã hoàn thành.");
        }
        List<SmartTestSessionQuestion> questions = sessionQuestionRepository.findAllForAttempt(attemptId);
        List<AssessmentAnswer> answers = answerRepository.findByAttemptId(attemptId);
        savePartialSkillResult(session, answers);
        List<SmartTestSkillResult> results = skillResultRepository
                .findBySessionAttemptIdOrderByIdSkillAsc(attemptId);
        finishAttempt(session, questions, answers, results);
        return buildResponse(session, questions, answers);
    }

    private void savePartialSkillResult(SmartTestSession session, List<AssessmentAnswer> answers) {
        if (answers.isEmpty()) return;
        Set<String> completed = skillResultRepository.findBySessionAttemptIdOrderByIdSkillAsc(session.getAttemptId())
                .stream().map(result -> result.getId().getSkill()).collect(Collectors.toSet());
        AssessmentAnswer latest = answers.stream()
                .max(Comparator.comparing(AssessmentAnswer::getAnsweredAt)).orElseThrow();
        String skill = latest.getQuestion().getSkill();
        if (completed.contains(skill)) return;
        int correct = (int) answers.stream()
                .filter(answer -> answer.getQuestion().getSkill().equals(skill))
                .filter(answer -> Boolean.TRUE.equals(answer.getIsCorrect())).count();
        int total = (int) answers.stream()
                .filter(answer -> answer.getQuestion().getSkill().equals(skill)).count();
        skillResultRepository.save(SmartTestSkillResult.builder()
                .id(new SmartTestSkillResult.SmartTestSkillResultId(session.getAttemptId(), skill))
                .session(session).estimatedLevel(latest.getQuestion().getJlptLevel())
                .masteryScore(percent(correct, total)).confidence("LOW").build());
    }

    private void initializeNextSkill(SmartTestSession session, String skill, String inheritedLevel,
                                     List<SmartTestSessionQuestion> questions) {
        List<SmartTestSessionQuestion> waitingQuestions = questions.stream()
                .filter(item -> item.getQuestion().getSkill().equals(skill))
                .toList();
        boolean alreadyAtInheritedLevel = waitingQuestions.size() == SmartTestAdaptivePolicy.BATCH_SIZE
                && waitingQuestions.stream()
                .allMatch(item -> item.getQuestion().getJlptLevel().equals(inheritedLevel));
        if (alreadyAtInheritedLevel) return;

        sessionQuestionRepository.deleteAll(waitingQuestions);
        sessionQuestionRepository.flush();
        questions.removeAll(waitingQuestions);
        appendBatch(session, skill, inheritedLevel, 1, questions);
    }

    private void advanceSkillIfBatchComplete(SmartTestSession session,
                                             SmartTestSessionQuestion answeredItem,
                                             List<SmartTestSessionQuestion> questions,
                                             List<AssessmentAnswer> answers) {
        String skill = answeredItem.getQuestion().getSkill();
        int batchNumber = answeredItem.getBatchNumber();
        if (skillResultRepository.existsByIdAttemptIdAndIdSkill(session.getAttemptId(), skill)) return;

        Set<Long> answeredIds = answers.stream().map(answer -> answer.getQuestion().getId()).collect(Collectors.toSet());
        List<SmartTestSessionQuestion> currentBatch = questions.stream()
                .filter(item -> item.getQuestion().getSkill().equals(skill))
                .filter(item -> item.getBatchNumber() == batchNumber)
                .toList();
        if (currentBatch.size() != SmartTestAdaptivePolicy.BATCH_SIZE
                || currentBatch.stream().anyMatch(item -> !answeredIds.contains(item.getQuestion().getId()))) return;

        Map<Long, AssessmentAnswer> answerByQuestion = answers.stream()
                .collect(Collectors.toMap(answer -> answer.getQuestion().getId(), Function.identity()));
        List<BatchScore> history = questions.stream()
                .filter(item -> item.getQuestion().getSkill().equals(skill))
                .collect(Collectors.groupingBy(SmartTestSessionQuestion::getBatchNumber))
                .entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new BatchScore(entry.getKey(), entry.getValue().get(0).getQuestion().getJlptLevel(),
                        (int) entry.getValue().stream()
                                .filter(item -> Boolean.TRUE.equals(
                                        answerByQuestion.get(item.getQuestion().getId()).getIsCorrect()))
                                .count()))
                .toList();
        Decision decision = adaptivePolicy.decide(history);
        if (decision.finished()) {
            saveSkillResult(session, skill, decision.estimatedLevel(), questions, answers,
                    confidenceForCompletedHistory(history, decision.estimatedLevel()));
            return;
        }

        boolean appended = appendBatch(session, skill, decision.nextLevel(), batchNumber + 1, questions);
        if (!appended) {
            saveSkillResult(session, skill, answeredItem.getQuestion().getJlptLevel(), questions, answers, "LOW");
        }
    }

    private boolean appendBatch(SmartTestSession session, String skill, String level, int batchNumber,
                                List<SmartTestSessionQuestion> questions) {
        List<AssessmentQuestion> candidates = SmartTestQuestionSelector.select(
                questionRepository.findByIsActiveTrueAndSkillAndJlptLevel(skill, level),
                questions.stream().map(SmartTestSessionQuestion::getQuestion).toList(),
                SmartTestAdaptivePolicy.BATCH_SIZE, preferredTags(session.getGoal()));
        if (candidates.size() < SmartTestAdaptivePolicy.BATCH_SIZE) {
            if (batchNumber == 1) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Ngân hàng câu hỏi chưa đủ 5 câu khác từ nhau " + skill + " ở cấp " + level + ".");
            }
            return false;
        }

        int nextOrder = questions.stream().map(SmartTestSessionQuestion::getOrderIndex)
                .max(Comparator.naturalOrder()).orElse(0) + 1;
        List<SmartTestSessionQuestion> batch = new ArrayList<>();
        for (AssessmentQuestion question : candidates.subList(0, SmartTestAdaptivePolicy.BATCH_SIZE)) {
            batch.add(SmartTestSessionQuestion.builder()
                    .id(new SmartTestSessionQuestion.SmartTestSessionQuestionId(
                            session.getAttemptId(), question.getId()))
                    .session(session)
                    .question(question)
                    .batchNumber(batchNumber)
                    .orderIndex(nextOrder++)
                    .build());
        }
        sessionQuestionRepository.saveAll(batch);
        questions.addAll(batch);
        questions.sort(Comparator.comparing(SmartTestSessionQuestion::getOrderIndex));
        return true;
    }

    private void saveSkillResult(SmartTestSession session, String skill, String estimatedLevel,
                                 List<SmartTestSessionQuestion> questions, List<AssessmentAnswer> answers,
                                 String confidence) {
        Set<Long> questionIds = questions.stream()
                .filter(item -> item.getQuestion().getSkill().equals(skill))
                .map(item -> item.getQuestion().getId()).collect(Collectors.toSet());
        int correct = (int) answers.stream()
                .filter(answer -> questionIds.contains(answer.getQuestion().getId()))
                .filter(answer -> Boolean.TRUE.equals(answer.getIsCorrect())).count();
        skillResultRepository.save(SmartTestSkillResult.builder()
                .id(new SmartTestSkillResult.SmartTestSkillResultId(session.getAttemptId(), skill))
                .session(session)
                .estimatedLevel(estimatedLevel)
                .masteryScore(percent(correct, questionIds.size()))
                .confidence(confidence)
                .build());
    }

    private void finishAttempt(SmartTestSession session, List<SmartTestSessionQuestion> questions,
                               List<AssessmentAnswer> answers, List<SmartTestSkillResult> results) {
        int totalCorrect = (int) answers.stream().filter(answer -> Boolean.TRUE.equals(answer.getIsCorrect())).count();
        BigDecimal overall = percent(totalCorrect, answers.size());
        double averageLevel = results.stream()
                .mapToInt(result -> SmartTestAdaptivePolicy.LEVELS.indexOf(result.getEstimatedLevel()) + 1)
                .average().orElse(SmartTestAdaptivePolicy.LEVELS.indexOf(session.getSelfReportedLevel()) + 1);
        int overallIndex = Math.max(0, Math.min(SmartTestAdaptivePolicy.LEVELS.size() - 1,
                (int) Math.round(averageLevel) - 1));

        session.setEstimatedOverallLevel(SmartTestAdaptivePolicy.LEVELS.get(overallIndex));
        session.setOverallMasteryScore(overall);
        boolean partial = answers.size() < questions.size() || results.size() < selectedSkills(questions).size();
        session.setReadinessLevel(persistedReadiness(
                readiness(session.getTargetLevel(), averageLevel - 1, partial)));
        session.getAttempt().setScorePercent(overall);
        session.getAttempt().setStatus("SUBMITTED");
        session.getAttempt().setSubmittedAt(LocalDateTime.now());
        attemptRepository.save(session.getAttempt());
        sessionRepository.save(session);
    }

    private SmartTestSession ownedSession(Long userId, Long attemptId) {
        AssessmentAttempt attempt = attemptRepository.findByIdAndUserId(attemptId, userId)
                .filter(item -> "SMART_TEST".equals(item.getAttemptType()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Không tìm thấy bài Smart Test."));
        SmartTestSession session = sessionRepository.findById(attemptId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Không tìm thấy bài Smart Test."));
        session.setAttempt(attempt);
        return session;
    }

    private SmartTestResponse buildResponse(SmartTestSession session,
                                            List<SmartTestSessionQuestion> questions,
                                            List<AssessmentAnswer> answers) {
        Set<Long> answeredIds = answers.stream().map(answer -> answer.getQuestion().getId()).collect(Collectors.toSet());
        List<SmartTestSkillResult> completed = skillResultRepository
                .findBySessionAttemptIdOrderByIdSkillAsc(session.getAttemptId());
        String activeSkill = "SUBMITTED".equals(session.getAttempt().getStatus())
                ? null : activeSkill(questions, completed);
        QuestionView current = questions.stream()
                .filter(item -> item.getQuestion().getSkill().equals(activeSkill))
                .filter(item -> !answeredIds.contains(item.getQuestion().getId()))
                .findFirst().map(this::toQuestion).orElse(null);
        ResultView result = "SUBMITTED".equals(session.getAttempt().getStatus())
                ? toResult(session, questions, answers) : null;
        int visibleTotal = "SUBMITTED".equals(session.getAttempt().getStatus())
                ? questions.size()
                : answers.size() + (int) questions.stream()
                .filter(item -> item.getQuestion().getSkill().equals(activeSkill))
                .filter(item -> !answeredIds.contains(item.getQuestion().getId()))
                .count();
        return new SmartTestResponse(session.getAttemptId(), session.getAttempt().getStatus(), answers.size(),
                visibleTotal, selectedSkills(questions),
                current == null ? null : withDisplayNumber(current, answers.size() + 1), result);
    }

    private static QuestionView withDisplayNumber(QuestionView question, int number) {
        return new QuestionView(question.id(), number, question.batchNumber(), question.skill(), question.jlptLevel(),
                question.tagCode(), question.difficulty(), question.title(), question.passageHtml(), question.audioUrl(),
                question.imageUrl(), question.questionText(), question.options());
    }

    private static List<String> selectedSkills(List<SmartTestSessionQuestion> questions) {
        Set<String> selected = questions.stream()
                .map(item -> item.getQuestion().getSkill())
                .collect(Collectors.toSet());
        return SKILL_ORDER.stream().filter(selected::contains).toList();
    }

    private static String activeSkill(List<SmartTestSessionQuestion> questions,
                                      List<SmartTestSkillResult> completed) {
        Set<String> completedNames = completed.stream()
                .map(result -> result.getId().getSkill())
                .collect(Collectors.toSet());
        return selectedSkills(questions).stream()
                .filter(skill -> !completedNames.contains(skill))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Không còn kỹ năng đang kiểm tra."));
    }

    private QuestionView toQuestion(SmartTestSessionQuestion item) {
        AssessmentQuestion question = item.getQuestion();
        return new QuestionView(question.getId(), item.getOrderIndex(), item.getBatchNumber(), question.getSkill(),
                question.getJlptLevel(), question.getKnowledgeTag().getCode(), question.getDifficulty(), question.getTitle(), question.getPassageHtml(),
                question.getAudioUrl(), question.getImageUrl(), question.getQuestionText(),
                List.of(text(question.getOption1()), text(question.getOption2()),
                        text(question.getOption3()), text(question.getOption4())));
    }

    private ResultView toResult(SmartTestSession session, List<SmartTestSessionQuestion> questions,
                                List<AssessmentAnswer> answers) {
        Map<String, SmartTestSkillResult> saved = skillResultRepository
                .findBySessionAttemptIdOrderByIdSkillAsc(session.getAttemptId()).stream()
                .collect(Collectors.toMap(item -> item.getId().getSkill(), Function.identity()));
        List<SkillResultView> skills = SKILL_ORDER.stream()
                .filter(saved::containsKey)
                .map(skill -> {
                    SmartTestSkillResult value = saved.get(skill);
                    List<AssessmentAnswer> skillAnswers = answers.stream()
                            .filter(answer -> answer.getQuestion().getSkill().equals(skill)).toList();
                    int correct = (int) skillAnswers.stream()
                            .filter(answer -> Boolean.TRUE.equals(answer.getIsCorrect())).count();
                    List<BatchResultView> batches = batchResults(skill, questions, skillAnswers);
                    boolean weakEvidence = weakLevelEvidence(value.getEstimatedLevel(), batches);
                    boolean partialSkill = skillAnswers.size() % SmartTestAdaptivePolicy.BATCH_SIZE != 0
                            || ("LOW".equals(value.getConfidence()) && !weakEvidence);
                    String confidence = weakEvidence ? "LOW" : resultConfidence(batches.size(), partialSkill);
                    String band = abilityBand(value.getEstimatedLevel(), batches, partialSkill);
                    return new SkillResultView(skill, value.getEstimatedLevel(), band, value.getMasteryScore(),
                            confidence, correct, skillAnswers.size(),
                            recommendation(value.getEstimatedLevel(), band, batches, partialSkill), batches,
                            grammarPointResults(skillAnswers));
                }).toList();
        int correct = (int) answers.stream().filter(answer -> Boolean.TRUE.equals(answer.getIsCorrect())).count();
        boolean partial = answers.size() < questions.size() || skills.size() < selectedSkills(questions).size();
        String confidence = overallConfidence(skills, partial);
        String band = overallAbilityBand(skills);
        String readiness = readiness(session.getTargetLevel(), overallAbilityPosition(skills), partial);
        List<String> recommendations = skills.stream()
                .sorted(Comparator.comparingDouble(AssessmentServiceImpl::abilityPosition))
                .limit(2)
                .map(skill -> skillLabel(skill.skill()) + ": " + skill.recommendation())
                .toList();
        return new ResultView(session.getEstimatedOverallLevel(), band, session.getOverallMasteryScore(),
                confidence, readiness, session.getTargetLevel(), partial,
                correct, answers.size(), selectedSkills(questions).size(), recommendations, skills);
    }

    private static List<BatchResultView> batchResults(String skill,
                                                       List<SmartTestSessionQuestion> questions,
                                                       List<AssessmentAnswer> answers) {
        Map<Long, AssessmentAnswer> answerByQuestion = answers.stream()
                .collect(Collectors.toMap(answer -> answer.getQuestion().getId(), Function.identity()));
        return questions.stream()
                .filter(item -> item.getQuestion().getSkill().equals(skill))
                .collect(Collectors.groupingBy(SmartTestSessionQuestion::getBatchNumber))
                .entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> {
                    List<SmartTestSessionQuestion> answered = entry.getValue().stream()
                            .filter(item -> answerByQuestion.containsKey(item.getQuestion().getId())).toList();
                    int correct = (int) answered.stream()
                            .filter(item -> Boolean.TRUE.equals(
                                    answerByQuestion.get(item.getQuestion().getId()).getIsCorrect()))
                            .count();
                    String level = entry.getValue().get(0).getQuestion().getJlptLevel();
                    return new BatchResultView(entry.getKey(), level, correct, answered.size());
                })
                .filter(batch -> batch.totalQuestions() > 0)
                .toList();
    }

    /** [JLAS2-24] Gom điểm yếu theo mẫu ngữ pháp CHÍNH (mẫu phụ không tính điểm). */
    private static List<GrammarPointResultView> grammarPointResults(List<AssessmentAnswer> skillAnswers) {
        Map<Long, List<AssessmentAnswer>> grouped = skillAnswers.stream()
                .filter(answer -> answer.getQuestion().getGrammarPoint() != null)
                .collect(Collectors.groupingBy(answer -> answer.getQuestion().getGrammarPoint().getId()));
        return grouped.entrySet().stream()
                .map(entry -> {
                    GrammarPoint point = entry.getValue().get(0).getQuestion().getGrammarPoint();
                    int correct = (int) entry.getValue().stream()
                            .filter(answer -> Boolean.TRUE.equals(answer.getIsCorrect())).count();
                    return new GrammarPointResultView(point.getId(), point.getPattern(),
                            lessonCode(point), lessonTitle(point), correct, entry.getValue().size(),
                            percent(correct, entry.getValue().size()));
                })
                .sorted(Comparator.comparing(GrammarPointResultView::masteryScore)
                        .thenComparing(GrammarPointResultView::pattern))
                .toList();
    }

    private static String lessonCode(GrammarPoint point) {
        return point.getLesson().getKnowledgeTag().getCode();
    }

    private static String lessonTitle(GrammarPoint point) {
        return point.getLesson().getTitle();
    }

    private static String confidenceForCompletedHistory(List<BatchScore> history, String estimatedLevel) {
        BatchScore last = history.get(history.size() - 1);
        boolean confirmed = history.size() >= 2
                && history.get(history.size() - 2).level().equals(last.level())
                && history.get(history.size() - 2).correctAnswers() == 3
                && last.correctAnswers() >= 2;
        if (!confirmed && last.level().equals(estimatedLevel) && last.correctAnswers() <= 2) return "LOW";
        if (history.size() >= 3) return "HIGH";
        return "MEDIUM";
    }

    static String resultConfidence(int completedBatches, boolean partial) {
        if (partial || completedBatches == 0) return "LOW";
        if (completedBatches >= 3) return "HIGH";
        return "MEDIUM";
    }

    static String overallConfidence(List<SkillResultView> skills, boolean partial) {
        if (partial || skills.size() <= 2) return "LOW";
        int minimum = skills.stream().mapToInt(skill -> confidenceRank(skill.confidence())).min().orElse(1);
        if (skills.size() < SKILL_ORDER.size()) minimum = Math.min(minimum, 2);
        return minimum >= 3 ? "HIGH" : minimum == 2 ? "MEDIUM" : "LOW";
    }

    private static int confidenceRank(String confidence) {
        return "HIGH".equals(confidence) ? 3 : "MEDIUM".equals(confidence) ? 2 : 1;
    }

    static String abilityBand(String estimatedLevel, List<BatchResultView> batches, boolean partial) {
        if (partial) return estimatedLevel + " (tạm tính)";
        if (batches.isEmpty()) return estimatedLevel;
        BatchResultView last = batches.get(batches.size() - 1);
        int estimatedIndex = levelIndex(estimatedLevel);
        int lastIndex = levelIndex(last.level());
        if (lastIndex > estimatedIndex) {
            return estimatedLevel + (last.correctAnswers() <= 1 ? " Căn bản" : " Thành thạo");
        }
        if (batches.size() > 1) {
            BatchResultView previous = batches.get(batches.size() - 2);
            if (lastIndex > levelIndex(previous.level()) && last.level().equals(estimatedLevel)) {
                return estimatedLevel + (last.correctAnswers() >= 5 ? " Căn bản" : " Nhập môn");
            }
            if (previous.level().equals(last.level()) && previous.correctAnswers() == 3
                    && last.correctAnswers() >= 2 && last.correctAnswers() <= 3) {
                return estimatedLevel + " Căn bản";
            }
        }
        return estimatedLevel + (last.correctAnswers() <= 2 ? " Nhập môn"
                : last.correctAnswers() == 3 ? " Căn bản" : " Thành thạo");
    }

    static boolean weakLevelEvidence(String estimatedLevel, List<BatchResultView> batches) {
        if (batches.isEmpty()) return false;
        BatchResultView last = batches.get(batches.size() - 1);
        if (batches.size() >= 2) {
            BatchResultView previous = batches.get(batches.size() - 2);
            if (previous.level().equals(last.level()) && previous.correctAnswers() == 3
                    && last.correctAnswers() >= 2) return false;
        }
        return last.level().equals(estimatedLevel) && last.totalQuestions() == 5
                && last.correctAnswers() <= 2;
    }

    static String overallAbilityBand(List<SkillResultView> skills) {
        if (skills.isEmpty()) return "Chưa đủ dữ liệu";
        if (skills.size() == 1) return skills.get(0).abilityBand();
        double position = overallAbilityPosition(skills);
        int index = Math.max(0, Math.min(SmartTestAdaptivePolicy.LEVELS.size() - 1,
                (int) Math.round(position)));
        double offset = position - index;
        return SmartTestAdaptivePolicy.LEVELS.get(index)
                + (offset < -0.1 ? " Nhập môn" : offset > 0.1 ? " Thành thạo" : " Căn bản");
    }

    private static double overallAbilityPosition(List<SkillResultView> skills) {
        if (skills.isEmpty()) return 0;
        double weightedTotal = 0;
        int totalWeight = 0;
        for (SkillResultView skill : skills) {
            int weight = confidenceRank(skill.confidence());
            weightedTotal += abilityPosition(skill) * weight;
            totalWeight += weight;
        }
        return weightedTotal / totalWeight;
    }

    private static double abilityPosition(SkillResultView skill) {
        double position = levelIndex(skill.estimatedLevel());
        String band = skill.abilityBand();
        if (band != null && band.endsWith("Thành thạo")) position += 0.2;
        else if (band != null && band.endsWith("Nhập môn")) position -= 0.2;
        return position;
    }

    private static String recommendation(String level, String band, List<BatchResultView> batches,
                                         boolean partial) {
        if (partial) return "Hoàn thành thêm một vòng để kết quả đủ tin cậy.";
        if (band.endsWith("Thành thạo") && !"N1".equals(level)) {
            return "Củng cố chắc " + level + " rồi luyện dần câu hỏi ở cấp kế tiếp.";
        }
        BatchResultView last = batches.isEmpty() ? null : batches.get(batches.size() - 1);
        if (last != null && last.correctAnswers() <= 2) {
            return "Ôn lại các điểm nền tảng của " + level + " trước khi tăng độ khó.";
        }
        if ("N1".equals(level)) return "Duy trì luyện đề N1 và tăng độ chính xác trong ngữ cảnh khó.";
        return "Tiếp tục luyện ở " + level + " để ổn định trước khi thử cấp cao hơn.";
    }

    private static String skillLabel(String skill) {
        return switch (skill) {
            case "KANJI" -> "Kanji";
            case "VOCABULARY" -> "Từ vựng";
            case "GRAMMAR" -> "Ngữ pháp";
            case "READING" -> "Đọc hiểu";
            case "LISTENING" -> "Nghe hiểu";
            default -> skill;
        };
    }

    private static int levelIndex(String level) {
        int index = SmartTestAdaptivePolicy.LEVELS.indexOf(level);
        return index < 0 ? 0 : index;
    }

    private static BigDecimal percent(int correct, int total) {
        if (total == 0) return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        return BigDecimal.valueOf(correct * 100.0 / total).setScale(2, RoundingMode.HALF_UP);
    }

    static String readiness(String targetLevel, double abilityPosition, boolean partial) {
        if (partial) return "PROVISIONAL";
        if (targetLevel == null || targetLevel.isBlank()) return "STABLE";
        double gap = abilityPosition - levelIndex(targetLevel);
        if (gap >= -0.25) return "READY";
        if (gap >= -0.75) return "NEAR";
        return "BUILDING";
    }

    /**
     * The existing database constraint only accepts LOW/MEDIUM/HIGH. Richer
     * readiness labels are derived for the response and must not be persisted.
     */
    static String persistedReadiness(String readiness) {
        return switch (readiness) {
            case "READY", "STABLE" -> "HIGH";
            case "NEAR" -> "MEDIUM";
            default -> "LOW";
        };
    }

    private static String normalizeLevel(String value) {
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return SmartTestAdaptivePolicy.LEVELS.contains(normalized) ? normalized : "N5";
    }

    static List<String> preferredTags(String goal) {
        return GOAL_TAG_PRIORITY.getOrDefault(goal, List.of());
    }

    private static String allowed(String value, Set<String> allowed, String message) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) throw badRequest(message);
        return normalized;
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
