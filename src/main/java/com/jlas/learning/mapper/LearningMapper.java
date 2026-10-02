package com.jlas.learning.mapper;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.learning.dto.CoverageGap;
import com.jlas.learning.dto.LearningPathItemDto;
import com.jlas.learning.dto.LearningPathResponse;
import com.jlas.learning.dto.LearningPhaseGroup;
import com.jlas.learning.dto.LearningStageResponse;
import com.jlas.learning.dto.LessonQuizResponse;
import com.jlas.learning.dto.LessonQuizResult;
import com.jlas.learning.dto.QuizAnswerKey;
import com.jlas.learning.entity.UserLearningPath;
import com.jlas.learning.entity.UserLearningPathItem;
import com.jlas.learning.entity.UserLearningStage;
import com.jlas.learning.enums.LearningPhase;
import com.jlas.learning.service.WarmUpFlashcardService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import org.springframework.stereotype.Component;

/** Maps persisted learning-path entities into the API read model and (de)serializes JSON snapshots. */
@Component
public class LearningMapper {
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };
    private static final TypeReference<List<CoverageGap>> GAP_LIST = new TypeReference<>() { };
    // [JLAS-W0108] Snapshot đề thi bất biến (learning_quiz_session).
    private static final TypeReference<List<LessonQuizResponse.LessonQuizQuestion>> QUIZ_QUESTION_LIST =
            new TypeReference<>() { };
    private static final TypeReference<Map<Long, QuizAnswerKey>> QUIZ_ANSWER_KEY = new TypeReference<>() { };

    // The app does not expose a Spring-managed ObjectMapper (webmvc starter without starter-json),
    // so this mapper owns its own instance for the small JSON snapshot columns it persists.
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * [JLAS-W0108] P2 — completion độc lập source lesson: mỗi item tự mang {@code study_status}.
     * Dùng cho lộ trình chính ({@code LearningServiceImpl}).
     */
    public LearningPathResponse toResponse(UserLearningPath path, List<UserLearningPathItem> items,
            boolean flashcardsCompleted) {
        return toResponse(path, items, List.of(), flashcardsCompleted);
    }

    /**
     * [JLAS-W0108] P6 — lộ trình nhiều chặng: kèm danh sách {@code stages} (level/order/status) để UI
     * hiển thị chặng hiện tại; scope cũ không phân chặng truyền {@code List.of()}.
     */
    public LearningPathResponse toResponse(UserLearningPath path, List<UserLearningPathItem> items,
            List<UserLearningStage> stages, boolean flashcardsCompleted) {
        return build(path, items, stages, flashcardsCompleted,
                item -> "STUDIED".equals(item.getStudyStatus()));
    }

    /** Legacy (replan) — completion theo lesson_progress; chuyển sang per-item ở P7. */
    public LearningPathResponse toResponse(UserLearningPath path, List<UserLearningPathItem> items,
            Set<Long> completedLessonIds) {
        return toResponse(path, items, completedLessonIds, false);
    }

    /** Legacy (replan) — completion theo lesson_progress; chuyển sang per-item ở P7. */
    public LearningPathResponse toResponse(UserLearningPath path, List<UserLearningPathItem> items,
            Set<Long> completedLessonIds, boolean flashcardsCompleted) {
        return build(path, items, List.of(), flashcardsCompleted,
                item -> item.getLesson() != null && completedLessonIds.contains(item.getLesson().getId()));
    }

    private LearningPathResponse build(UserLearningPath path, List<UserLearningPathItem> items,
            List<UserLearningStage> stages, boolean flashcardsCompleted,
            Function<UserLearningPathItem, Boolean> completedOf) {
        Map<LearningPhase, List<UserLearningPathItem>> byPhase = new LinkedHashMap<>();
        for (var item : items) {
            byPhase.computeIfAbsent(LearningPhase.valueOf(item.getPhase()), key -> new ArrayList<>()).add(item);
        }
        List<LearningPhaseGroup> groups = new ArrayList<>();
        int totalLessons = 0;
        int totalMinutes = 0;
        int completed = 0;
        for (var entry : byPhase.entrySet()) {
            List<LearningPathItemDto> dtos = new ArrayList<>();
            var cardItems = entry.getValue().stream().filter(WarmUpFlashcardService::isFlashcardItem).toList();
            boolean addedCards = false;
            for (var item : entry.getValue()) {
                if (WarmUpFlashcardService.isFlashcardItem(item)) {
                    if (!addedCards) dtos.add(toFlashcardGroup(cardItems, flashcardsCompleted));
                    addedCards = true;
                } else {
                    dtos.add(toItem(item, completedOf.apply(item)));
                }
            }
            int minutes = dtos.stream().mapToInt(LearningPathItemDto::estimatedMinutes).sum();
            long dayCount = dtos.stream().mapToInt(LearningPathItemDto::dayNumber).distinct().count();
            groups.add(new LearningPhaseGroup(entry.getKey(), dtos, dtos.size(), minutes, (int) dayCount));
            totalLessons += dtos.size();
            totalMinutes += minutes;
            completed += (int) dtos.stream().filter(LearningPathItemDto::completed).count();
        }
        int totalDays = groups.stream().flatMap(group -> group.items().stream())
                .mapToInt(LearningPathItemDto::dayNumber).max().orElse(0);
        boolean warmUpCompleted = groups.stream().filter(group -> group.phase() == LearningPhase.WARM_UP)
                .anyMatch(group -> !group.items().isEmpty() && group.items().stream().allMatch(LearningPathItemDto::completed));
        List<LearningStageResponse> stageResponses = stages.stream()
                .map(stage -> new LearningStageResponse(stage.getId(), stage.getLevel(), stage.getStageIndex(),
                        stage.getStatus(), stage.getCurriculumVersion(), stage.getAssessmentCycle(),
                        stageFinalAvailable(stage, items)))
                .toList();
        return new LearningPathResponse(path.getId(), path.getUser().getId(), path.getAssessmentAttemptId(),
                path.getGoal(), path.getTargetLevel(),
                path.getCurrentLevel(), path.getMinutesPerDay(), path.getStatus(), path.getVersion(), path.getCreatedAt(),
                groups, readCoverageGaps(path.getCoverageGapsJson()),
                totalLessons, totalMinutes, totalDays, completed,
                warmUpCompleted, path.getGatePassedAt() != null, readAiPlan(path.getAiPlanJson()), stageResponses,
                path.getCompletedAt());
    }

    /** [JLAS-W0108] Chặng ACTIVE và mọi item của chặng đã STUDIED -> bài final sẵn sàng phát. */
    private static boolean stageFinalAvailable(UserLearningStage stage, List<UserLearningPathItem> items) {
        if (!"ACTIVE".equals(stage.getStatus())) return false;
        return items.stream()
                .filter(item -> stage.getId().equals(item.getStageId()))
                .allMatch(item -> "STUDIED".equals(item.getStudyStatus()));
    }

    private LearningPathItemDto toFlashcardGroup(List<UserLearningPathItem> items, boolean completed) {
        var first = items.stream().min(java.util.Comparator.comparing(UserLearningPathItem::getOrderIndex)).orElseThrow();
        var source = toItem(first, completed);
        int minutes = items.stream().mapToInt(UserLearningPathItem::getEstimatedMinutes).sum();
        return new LearningPathItemDto(null, source.lessonId(), "Ôn tập Kanji & Từ vựng", source.textbookId(),
                source.unitId(), source.level(), "WARM_UP_FLASHCARDS",
                "Một bộ thẻ tổng hợp kiến thức còn yếu. Lật thẻ và đánh dấu đã nhớ để hoàn thành ôn tập.",
                source.matchedTagCodes(), source.knowledgePointCode(), source.knowledgePointName(),
                null, null, null, source.vocabularyEntryId(), null, null, null,
                source.dayNumber(), source.orderIndex(), minutes, completed, source.started());
    }

    private LearningPathItemDto toItem(UserLearningPathItem item, boolean completed) {
        var lesson = item.getLesson();
        Long textbookId = null;
        Long unitId = null;
        Long lessonId = null;
        String level = null;
        String knowledgePointCode = null;
        String knowledgePointName = null;
        // [JLAS-W0108] P2c — lesson nguồn có thể NULL (activity trỏ grammar_point / vocabulary_entry
        // thay vì bài nguồn); mọi trường suy từ lesson phải an toàn với null.
        if (lesson != null) {
            lessonId = lesson.getId();
            if (lesson.getUnit() != null) {
                unitId = lesson.getUnit().getId();
                if (lesson.getUnit().getTextbook() != null) {
                    textbookId = lesson.getUnit().getTextbook().getId();
                    level = lesson.getUnit().getTextbook().getJlptLevel();
                }
            }
            // [JLAS-W0108] Điểm kiến thức chính (knowledge tag) để nhóm và trình bày lộ trình
            // theo từng điểm kiến thức thay vì liệt kê tuần tự theo giáo trình.
            knowledgePointCode = lesson.getKnowledgeTag() == null ? null : lesson.getKnowledgeTag().getCode();
            knowledgePointName = lesson.getKnowledgeTag() == null ? null : lesson.getKnowledgeTag().getName();
        }
        // [JLAS-W0108] Warm-up GRAMMAR trỏ tới đúng mẫu ngữ pháp đã sai; lúc đó title = pattern.
        var grammarPoint = item.getGrammarPoint();
        Long grammarPointId = grammarPoint == null ? null : grammarPoint.getId();
        String grammarPointPattern = grammarPoint == null ? null : grammarPoint.getPattern();
        String grammarPointMeaning = grammarPoint == null ? null : grammarPoint.getMeaning();
        // [JLAS-W0108] Warm-up KANJI/VOCAB trỏ tới đúng từ vựng đã sai; lúc đó title = expression.
        var vocabularyEntry = item.getVocabularyEntry();
        Long vocabularyEntryId = vocabularyEntry == null ? null : vocabularyEntry.getId();
        String vocabularyExpression = vocabularyEntry == null ? null : vocabularyEntry.getExpression();
        String vocabularyReading = vocabularyEntry == null ? null : vocabularyEntry.getReading();
        String vocabularyMeaning = vocabularyEntry == null ? null : vocabularyEntry.getMeaning();
        String title;
        if (grammarPoint != null) {
            title = grammarPoint.getPattern();
        } else if (vocabularyEntry != null) {
            title = vocabularyEntry.getExpression();
        } else if (lesson != null) {
            title = personalLessonTitle(lesson);
        } else {
            title = item.getReason() != null && !item.getReason().isBlank()
                    ? item.getReason() : "Hoạt động học tập";
        }
        boolean started = !"NOT_STARTED".equals(item.getStudyStatus());
        return new LearningPathItemDto(item.getId(), lessonId, title, textbookId, unitId, level,
                item.getReasonCode(), item.getReason(), readMatchedTags(item.getMatchedTagCodes()),
                knowledgePointCode, knowledgePointName,
                grammarPointId, grammarPointPattern, grammarPointMeaning,
                vocabularyEntryId, vocabularyExpression, vocabularyReading, vocabularyMeaning,
                item.getDayNumber(), item.getOrderIndex(), item.getEstimatedMinutes(), completed, started);
    }

    // [JLAS-W0108] Lát D — bỏ số thứ tự "Bài N:" khỏi title của bài học goal/đọc/nghe để lộ trình
    // đọc thành các chủ đề kiến thức thay vì một danh sách đánh số giáo trình ("Bài 1, 2, 3…").
    private String personalLessonTitle(com.jlas.dictionary.entity.Lesson lesson) {
        String title = lesson.getTitle();
        if (title == null || title.isBlank()) return title;
        String stripped = title.replaceFirst("(?i)^bài\\s*\\d+\\s*[:：]\\s*", "").trim();
        return stripped.isEmpty() ? title : stripped;
    }

    public String writeMatchedTags(Set<String> tags) {
        try {
            return OBJECT_MAPPER.writeValueAsString(new TreeSet<>(tags));
        } catch (Exception exception) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "Failed to serialize matched tags.");
        }
    }

    public String writeCoverageGaps(List<CoverageGap> gaps) {
        try {
            return OBJECT_MAPPER.writeValueAsString(gaps);
        } catch (Exception exception) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "Failed to serialize coverage gaps.");
        }
    }

    public List<String> readMatchedTags(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return OBJECT_MAPPER.readValue(json, STRING_LIST);
        } catch (Exception exception) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "Failed to read matched tags snapshot.");
        }
    }

    private List<CoverageGap> readCoverageGaps(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return OBJECT_MAPPER.readValue(json, GAP_LIST);
        } catch (Exception exception) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "Failed to read coverage gaps snapshot.");
        }
    }

    /** [JLAS-W0108] Parse kế hoạch narrative Gemini; {@code null} nếu chưa sinh/JSON hỏng (UI fallback). */
    private com.fasterxml.jackson.databind.JsonNode readAiPlan(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return OBJECT_MAPPER.readTree(json);
        } catch (Exception exception) {
            return null;
        }
    }

    /** [JLAS-W0108] Serialize snapshot câu hỏi đã phát (không chứa đáp án) vào session đề thi. */
    public String writeQuizQuestions(List<LessonQuizResponse.LessonQuizQuestion> questions) {
        try {
            return OBJECT_MAPPER.writeValueAsString(questions);
        } catch (Exception exception) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "Failed to serialize quiz questions.");
        }
    }

    /** [JLAS-W0108] Đọc lại snapshot câu hỏi đã phát. */
    public List<LessonQuizResponse.LessonQuizQuestion> readQuizQuestions(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return OBJECT_MAPPER.readValue(json, QUIZ_QUESTION_LIST);
        } catch (Exception exception) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "Failed to read quiz questions snapshot.");
        }
    }

    /** [JLAS-W0108] Serialize đáp án (questionId -> correctOption/explanation) vào snapshot đề thi. */
    public String writeQuizAnswerKey(Map<Long, QuizAnswerKey> answerKey) {
        try {
            return OBJECT_MAPPER.writeValueAsString(answerKey);
        } catch (Exception exception) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "Failed to serialize quiz answer key.");
        }
    }

    /** [JLAS-W0108] Đọc lại đáp án snapshot. */
    public Map<Long, QuizAnswerKey> readQuizAnswerKey(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return OBJECT_MAPPER.readValue(json, QUIZ_ANSWER_KEY);
        } catch (Exception exception) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "Failed to read quiz answer key snapshot.");
        }
    }

    /** [JLAS-W0108] Serialize kết quả đã chấm để trả lại nguyên vẹn khi nộp lại (idempotent). */
    public String writeQuizResult(LessonQuizResult result) {
        try {
            return OBJECT_MAPPER.writeValueAsString(result);
        } catch (Exception exception) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "Failed to serialize quiz result.");
        }
    }

    /** [JLAS-W0108] Đọc lại kết quả đã chấm. */
    public LessonQuizResult readQuizResult(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return OBJECT_MAPPER.readValue(json, LessonQuizResult.class);
        } catch (Exception exception) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "Failed to read quiz result snapshot.");
        }
    }
}
