package com.jlas.learning.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jlas.auth.security.CurrentUser;
import com.jlas.assessment.entity.AssessmentQuestion;
import com.jlas.assessment.entity.ConversionRule;
import com.jlas.assessment.entity.RuleExample;
import com.jlas.assessment.entity.RulePattern;
import com.jlas.dictionary.entity.Lesson;
import com.jlas.dictionary.entity.Unit;
import com.jlas.learning.dto.OnyomiCourseView;
import com.jlas.learning.dto.OnyomiFinalView;
import com.jlas.learning.dto.OnyomiLessonStatus;
import com.jlas.learning.dto.OnyomiLessonView;
import com.jlas.learning.dto.OnyomiRulePageView;
import com.jlas.learning.repository.OnyomiCourseRepository;
import com.jlas.learning.repository.OnyomiCourseRepository.ProgressRef;
import com.jlas.learning.repository.OnyomiCourseRepository.QuizRef;
import com.jlas.learning.repository.OnyomiCourseRepository.QuizScore;
import com.jlas.learning.repository.OnyomiCourseRepository.QuizSize;
import com.jlas.learning.service.LessonQuizService;
import com.jlas.learning.service.OnyomiCourseService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Implements {@link OnyomiCourseService} on the Onyomi textbook seeded by scripts/onyomi. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OnyomiCourseServiceImpl implements OnyomiCourseService {

    private static final String QUIZ_LESSON = "LESSON";
    private static final String QUIZ_UNIT_FINAL = "UNIT_FINAL";
    private static final String PROGRESS_COMPLETED = "COMPLETED";
    private static final String PROGRESS_IN_PROGRESS = "IN_PROGRESS";
    private static final BigDecimal PASS_MARK = BigDecimal.valueOf(LessonQuizService.PASS_PERCENT);
    /** Example labels shown on a lesson tile of the course page. */
    private static final int CARD_EXAMPLES = 4;
    /** The romaji in an example breakdown such as "Công → コウ (kou) · 工場 こうじょう: nhà máy". */
    private static final Pattern BREAKDOWN_ROMAJI = Pattern.compile("\\(([^)]+)\\)");
    private static final String BREAKDOWN_WORD = " · ";
    /** Roles that review the course content, so every lesson and final test is open to them. */
    private static final List<String> REVIEWER_ROLES = List.of("ROLE_CONTENT_ADMIN", "ROLE_ADMIN", "ROLE_SUPER_ADMIN");

    private final OnyomiCourseRepository repository;
    private final ObjectMapper objectMapper;

    @Override
    public OnyomiCourseView course(Long userId) {
        Course course = load(userId);
        Map<Long, List<ConversionRule>> rulesByLesson = course.lessons().isEmpty() ? Map.of()
                : groupBy(repository.findRules(course.lessonIds()), rule -> rule.getLesson().getId());
        List<Long> ruleIds = rulesByLesson.values().stream().flatMap(List::stream).map(ConversionRule::getId).toList();
        Map<Long, List<RuleExample>> examples = ruleIds.isEmpty() ? Map.of()
                : groupBy(repository.findExamples(ruleIds), example -> example.getRule().getId());

        List<OnyomiCourseView.Stage> stages = course.stages().stream()
                .map(stage -> new OnyomiCourseView.Stage(
                        stage.unit().getId(),
                        stage.unit().getOrderIndex(),
                        stage.unit().getTitle(),
                        stage.unit().getDescription(),
                        stage.lessons().stream()
                                .map(state -> card(state,
                                        rulesByLesson.getOrDefault(state.lesson().getId(), List.of()), examples))
                                .toList(),
                        stage.completedLessons(),
                        new OnyomiCourseView.FinalTest(stage.finalQuizId(), stage.finalUnlocked(),
                                stage.finalPassed(), percent(stage.finalBest()))))
                .toList();
        int total = course.lessons().size();
        int completed = (int) course.lessons().stream().filter(LessonState::completed).count();
        Long current = course.lessons().stream()
                .filter(state -> state.status() != OnyomiLessonStatus.LOCKED && !state.completed())
                .map(state -> state.lesson().getId())
                .findFirst()
                .orElse(null);
        return new OnyomiCourseView(stages, total, completed, total == 0 ? 0 : Math.round(completed * 100f / total),
                current, course.unrestricted());
    }

    @Override
    public Optional<OnyomiLessonView> lesson(Long userId, Long lessonId) {
        Course course = load(userId);
        Optional<LessonState> found = course.lesson(lessonId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        LessonState state = found.get();
        StageState stage = course.stageOf(state);
        Lesson lesson = state.lesson();
        List<OnyomiLessonView.Rule> rules = ruleViews(repository.findRules(List.of(lessonId)));
        int exampleCount = rules.stream().mapToInt(rule -> rule.examples().size()).sum();
        int position = course.lessons().indexOf(state);
        LessonState previous = position > 0 ? course.lessons().get(position - 1) : null;
        LessonState next = position + 1 < course.lessons().size() ? course.lessons().get(position + 1) : null;
        boolean lastInStage = stage.lessons().get(stage.lessons().size() - 1).equals(state);
        return Optional.of(new OnyomiLessonView(
                lesson.getId(),
                lesson.getOrderIndex(),
                lesson.getTitle(),
                lesson.getDescription(),
                lesson.getEstimatedMinutes(),
                stage.unit().getId(),
                stage.unit().getOrderIndex(),
                stage.unit().getTitle(),
                state.status().name(),
                percent(state.bestScore()),
                rules,
                exampleCount,
                perAttempt(course.questionCounts().get(state.practiceQuizId())),
                link(course, previous),
                link(course, next),
                lastInStage,
                stage.finalUnlocked()));
    }

    @Override
    public Optional<OnyomiRulePageView> rule(Long userId, Long ruleId) {
        Optional<ConversionRule> rule = repository.findRule(ruleId);
        if (rule.isEmpty()) {
            return Optional.empty();
        }
        Course course = load(userId);
        Optional<LessonState> found = course.lesson(rule.get().getLesson().getId());
        if (found.isEmpty()) {
            return Optional.empty();
        }
        LessonState state = found.get();
        StageState stage = course.stageOf(state);
        return Optional.of(new OnyomiRulePageView(
                ruleViews(List.of(rule.get())).get(0),
                state.lesson().getId(),
                state.lesson().getOrderIndex(),
                state.lesson().getTitle(),
                state.status().name(),
                stage.unit().getOrderIndex(),
                stage.unit().getTitle()));
    }

    @Override
    public Optional<OnyomiFinalView> stageFinal(Long userId, Long unitId) {
        Course course = load(userId);
        Optional<StageState> found = course.stage(unitId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        StageState stage = found.get();
        int index = course.stages().indexOf(stage);
        StageState nextStage = index + 1 < course.stages().size() ? course.stages().get(index + 1) : null;
        LessonState nextLesson = nextStage == null ? null : nextStage.lessons().get(0);
        return Optional.of(new OnyomiFinalView(
                unitId,
                stage.unit().getOrderIndex(),
                stage.unit().getTitle(),
                stage.unit().getDescription(),
                stage.finalQuizId(),
                stage.finalUnlocked(),
                stage.finalPassed(),
                percent(stage.finalBest()),
                stage.completedLessons(),
                stage.lessons().size(),
                link(course, nextLesson)));
    }

    @Override
    public boolean isLessonLocked(Long userId, Long lessonId) {
        return load(userId).lesson(lessonId)
                .map(state -> state.status() == OnyomiLessonStatus.LOCKED)
                .orElse(false);
    }

    @Override
    public boolean isFinalLocked(Long userId, Long unitId) {
        return load(userId).stage(unitId)
                .map(stage -> !stage.finalUnlocked())
                .orElse(false);
    }

    @Override
    public boolean isCourseLesson(Long lessonId) {
        return lessonId != null && repository.countCourseLesson(lessonId) > 0;
    }

    /**
     * Reads the course in order together with the learner's progress, and works out what is unlocked.
     * A content reviewer gets every lesson and final test unlocked; their progress is still recorded.
     */
    private Course load(Long userId) {
        boolean unrestricted = isReviewer();
        List<Lesson> lessons = repository.findCourseLessons();
        if (lessons.isEmpty()) {
            return new Course(List.of(), List.of(), Map.of(), unrestricted);
        }
        List<Long> lessonIds = lessons.stream().map(Lesson::getId).toList();
        Map<Long, List<Lesson>> lessonsByUnit = groupBy(lessons, lesson -> lesson.getUnit().getId());

        Map<Long, Long> practiceQuiz = new HashMap<>();
        Map<Long, Long> finalQuiz = new HashMap<>();
        // ordered by id, so when a lesson or unit has two quizzes of a kind the older one is used
        for (QuizRef quiz : repository.findQuizzes(lessonIds, List.copyOf(lessonsByUnit.keySet()))) {
            switch (quiz.getQuizType()) {
                case QUIZ_LESSON -> putIfPresent(practiceQuiz, quiz.getLessonId(), quiz.getId());
                case QUIZ_UNIT_FINAL -> putIfPresent(finalQuiz, quiz.getUnitId(), quiz.getId());
                default -> { }
            }
        }
        List<Long> quizIds = new ArrayList<>(practiceQuiz.values());
        quizIds.addAll(finalQuiz.values());
        Map<Long, Long> questionCounts = new HashMap<>();
        Map<Long, BigDecimal> bestScores = new HashMap<>();
        if (!quizIds.isEmpty()) {
            for (QuizSize size : repository.countQuestions(quizIds)) {
                questionCounts.put(size.getQuizId(), size.getQuestions());
            }
            if (userId != null) {
                for (QuizScore score : repository.findBestScores(userId, quizIds)) {
                    putIfPresent(bestScores, score.getQuizId(), score.getBestScore());
                }
            }
        }
        Map<Long, String> progress = new HashMap<>();
        if (userId != null) {
            for (ProgressRef ref : repository.findProgress(userId, lessonIds)) {
                progress.put(ref.getLessonId(), ref.getStatus());
            }
        }

        List<StageState> stages = new ArrayList<>();
        List<LessonState> ordered = new ArrayList<>();
        boolean stageOpen = true;
        for (List<Lesson> unitLessons : lessonsByUnit.values()) {
            boolean previousCompleted = stageOpen || unrestricted;
            boolean allCompleted = true;
            List<LessonState> states = new ArrayList<>();
            for (Lesson lesson : unitLessons) {
                String lessonProgress = progress.get(lesson.getId());
                boolean completed = PROGRESS_COMPLETED.equals(lessonProgress);
                OnyomiLessonStatus status;
                if (completed) {
                    status = OnyomiLessonStatus.COMPLETED;
                } else if (!previousCompleted) {
                    status = OnyomiLessonStatus.LOCKED;
                } else if (PROGRESS_IN_PROGRESS.equals(lessonProgress)) {
                    status = OnyomiLessonStatus.IN_PROGRESS;
                } else {
                    status = OnyomiLessonStatus.AVAILABLE;
                }
                Long practice = practiceQuiz.get(lesson.getId());
                LessonState state = new LessonState(lesson, status, practice == null ? null : bestScores.get(practice),
                        practice);
                states.add(state);
                ordered.add(state);
                previousCompleted = completed || unrestricted;
                allCompleted &= completed;
            }
            Unit unit = unitLessons.get(0).getUnit();
            Long finalId = finalQuiz.get(unit.getId());
            BigDecimal finalBest = finalId == null ? null : bestScores.get(finalId);
            boolean finalPassed = finalBest != null && finalBest.compareTo(PASS_MARK) >= 0;
            stages.add(new StageState(unit, states, finalId, finalBest, allCompleted || unrestricted, finalPassed));
            // the next stage opens once this stage's final test is passed, or without one once every lesson is done
            stageOpen = finalId != null ? finalPassed : allCompleted;
        }
        return new Course(stages, ordered, questionCounts, unrestricted);
    }

    private static boolean isReviewer() {
        return REVIEWER_ROLES.stream().anyMatch(CurrentUser::hasRole);
    }

    private OnyomiCourseView.LessonCard card(LessonState state, List<ConversionRule> rules,
                                             Map<Long, List<RuleExample>> examples) {
        List<String> sounds = rules.stream()
                .map(rule -> rule.getSourceSound() + " → " + rule.getTargetSound())
                .toList();
        // take the examples in turn from each rule so every rule of the lesson shows up on the tile
        List<String> labels = new ArrayList<>(CARD_EXAMPLES);
        for (int turn = 0; labels.size() < CARD_EXAMPLES; turn++) {
            boolean added = false;
            for (ConversionRule rule : rules) {
                List<RuleExample> ruleExamples = examples.getOrDefault(rule.getId(), List.of());
                if (turn < ruleExamples.size() && labels.size() < CARD_EXAMPLES) {
                    RuleExample example = ruleExamples.get(turn);
                    labels.add(example.getExampleExpression() + " (" + example.getKanji().getCharacter() + ")");
                    added = true;
                }
            }
            if (!added) {
                break;
            }
        }
        Lesson lesson = state.lesson();
        return new OnyomiCourseView.LessonCard(lesson.getId(), lesson.getOrderIndex(), lesson.getTitle(),
                lesson.getDescription(), state.status().name(), percent(state.bestScore()), sounds, labels);
    }

    private List<OnyomiLessonView.Rule> ruleViews(List<ConversionRule> rules) {
        if (rules.isEmpty()) {
            return List.of();
        }
        List<Long> ruleIds = rules.stream().map(ConversionRule::getId).toList();
        Map<Long, List<RuleExample>> examples = groupBy(repository.findExamples(ruleIds),
                example -> example.getRule().getId());
        Map<Long, List<RulePattern>> patterns = groupBy(repository.findPatterns(ruleIds),
                pattern -> pattern.getRule().getId());
        Map<Long, List<AssessmentQuestion>> exceptions = groupBy(repository.findExceptionQuestions(ruleIds),
                question -> question.getConversionRule().getId());
        return rules.stream()
                .map(rule -> new OnyomiLessonView.Rule(
                        rule.getId(),
                        rule.getSourceSound(),
                        rule.getTargetSound(),
                        rule.getTitle(),
                        rule.getDescription(),
                        rule.getTip(),
                        rule.getNote(),
                        patterns.getOrDefault(rule.getId(), List.of()).stream()
                                .map(pattern -> new OnyomiLessonView.Step(
                                        pattern.getStep(), pattern.getTitle(), pattern.getContent()))
                                .toList(),
                        examples.getOrDefault(rule.getId(), List.of()).stream().map(this::example).toList(),
                        exceptions.getOrDefault(rule.getId(), List.of()).stream()
                                .map(this::exception)
                                .filter(Objects::nonNull)
                                .toList()))
                .toList();
    }

    /** An example; its breakdown reads "Công → コウ (kou) · 工場 こうじょう: nhà máy". */
    private OnyomiLessonView.Example example(RuleExample example) {
        String breakdown = example.getBreakdown() == null ? "" : example.getBreakdown();
        Matcher romaji = BREAKDOWN_ROMAJI.matcher(breakdown);
        String word = null;
        String wordReading = null;
        String meaning = null;
        int wordStart = breakdown.indexOf(BREAKDOWN_WORD);
        if (wordStart >= 0) {
            String[] wordAndMeaning = breakdown.substring(wordStart + BREAKDOWN_WORD.length()).split(": ", 2);
            String[] wordParts = wordAndMeaning[0].trim().split(" ", 2);
            word = wordParts[0];
            wordReading = wordParts.length > 1 ? wordParts[1] : null;
            meaning = wordAndMeaning.length > 1 ? wordAndMeaning[1] : null;
        }
        return new OnyomiLessonView.Example(
                example.getKanji().getCharacter(),
                example.getExampleExpression(),
                example.getExampleReading(),
                romaji.find() ? romaji.group(1) : null,
                word,
                wordReading,
                meaning,
                null);
    }

    /** An exception to a rule, read from the context the generator stores on its EXCEPTION question. */
    private OnyomiLessonView.Example exception(AssessmentQuestion question) {
        if (question.getContextJson() == null) {
            return null;
        }
        try {
            JsonNode context = objectMapper.readTree(question.getContextJson());
            return new OnyomiLessonView.Example(
                    text(context, "kanji"),
                    text(context, "hanViet"),
                    text(context, "reading"),
                    text(context, "romaji"),
                    text(context, "word"),
                    text(context, "wordReading"),
                    text(context, "meaning"),
                    text(context, "note"));
        } catch (JsonProcessingException exception) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    private OnyomiLessonView.LessonLink link(Course course, LessonState state) {
        if (state == null) {
            return null;
        }
        Lesson lesson = state.lesson();
        return new OnyomiLessonView.LessonLink(lesson.getId(), course.stageOf(state).unit().getOrderIndex(),
                lesson.getOrderIndex(), lesson.getTitle(), state.status() != OnyomiLessonStatus.LOCKED);
    }

    /** Questions one attempt of a quiz shows. */
    private static int perAttempt(Long questionCount) {
        return questionCount == null ? 0 : (int) Math.min(questionCount, LessonQuizService.QUESTIONS_PER_ATTEMPT);
    }

    private static Integer percent(BigDecimal score) {
        return score == null ? null : score.setScale(0, RoundingMode.HALF_UP).intValue();
    }

    private static <K, V> void putIfPresent(Map<K, V> map, K key, V value) {
        if (key != null && value != null) {
            map.putIfAbsent(key, value);
        }
    }

    private static <K, V> Map<K, List<V>> groupBy(Collection<V> values, Function<V, K> key) {
        Map<K, List<V>> groups = new LinkedHashMap<>();
        for (V value : values) {
            groups.computeIfAbsent(key.apply(value), ignored -> new ArrayList<>()).add(value);
        }
        return groups;
    }

    /** One lesson and where the learner stands on it; {@code bestScore} is on its practice quiz. */
    private record LessonState(Lesson lesson, OnyomiLessonStatus status, BigDecimal bestScore,
                               Long practiceQuizId) {
        boolean completed() {
            return status == OnyomiLessonStatus.COMPLETED;
        }
    }

    /** One stage; its final test is unlocked once every lesson is completed. */
    private record StageState(Unit unit, List<LessonState> lessons, Long finalQuizId, BigDecimal finalBest,
                              boolean finalUnlocked, boolean finalPassed) {
        int completedLessons() {
            return (int) lessons.stream().filter(LessonState::completed).count();
        }
    }

    /**
     * The course in order; {@code questionCounts} maps quiz id to its number of questions and
     * {@code unrestricted} is set for a content reviewer, who has everything unlocked.
     */
    private record Course(List<StageState> stages, List<LessonState> lessons, Map<Long, Long> questionCounts,
                          boolean unrestricted) {
        List<Long> lessonIds() {
            return lessons.stream().map(state -> state.lesson().getId()).toList();
        }

        Optional<LessonState> lesson(Long lessonId) {
            return lessons.stream().filter(state -> state.lesson().getId().equals(lessonId)).findFirst();
        }

        Optional<StageState> stage(Long unitId) {
            return stages.stream().filter(stage -> stage.unit().getId().equals(unitId)).findFirst();
        }

        StageState stageOf(LessonState state) {
            return stages.stream().filter(stage -> stage.lessons().contains(state)).findFirst().orElseThrow();
        }
    }
}
