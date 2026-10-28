package com.jlas.learning.service;

import com.jlas.assessment.entity.UserLearningSurvey;
import com.jlas.assessment.repository.UserLearningSurveyRepository;
import com.jlas.learning.dto.CommunicationGuide;
import com.jlas.learning.entity.LearningSurveyOption;
import com.jlas.learning.entity.LearningTopic;
import com.jlas.learning.entity.LessonLearningScope;
import com.jlas.learning.entity.UserGoalSurveySelection;
import com.jlas.learning.entity.UserLearningPath;
import com.jlas.learning.repository.LearningSurveyOptionRepository;
import com.jlas.learning.repository.LearningTopicRepository;
import com.jlas.learning.repository.LessonLearningScopeRepository;
import com.jlas.learning.repository.UserGoalSurveySelectionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** [JLAS-W0108] Owner 2026-10-19: groups a communication roadmap by topic for display. Read-only; never locks. */
@Service
@RequiredArgsConstructor
public class CommunicationGuideService {
    private final LessonLearningScopeRepository scopes;
    private final LearningTopicRepository topics;
    private final UserGoalSurveySelectionRepository selections;
    private final LearningSurveyOptionRepository options;
    private final UserLearningSurveyRepository surveys;
    private final com.jlas.dictionary.repository.TextbookRepository textbooks;

    // §2.24 (owner 2026-10-19, Q5): BrSE links point at J-LAS textbooks — N4: the Learning N4 copy; N3: the N3 textbook.
    static final String N4_BOOK_SOURCE_CODE = "LP-OWNED-SEKAI-N4-CORE";
    static final String N3_BOOK_TITLE = "Tiếng Nhật N3";

    /** Returns {@code null} for JLPT paths, or when the path's lessons carry no topic scope. */
    @Transactional(readOnly = true)
    public CommunicationGuide guide(UserLearningPath path, List<Long> lessonIdsInPath) {
        if (path == null || !GoalPathPolicy.isCommunicationGoal(path) || lessonIdsInPath.isEmpty()) return null;
        Map<Long, Long> topicByLesson = new LinkedHashMap<>();
        scopes.findByLessonIdIn(lessonIdsInPath).forEach(scope ->
                topicByLesson.putIfAbsent(scope.getLessonId(), scope.getTopicId()));
        List<Long> topicIds = topicByLesson.values().stream().filter(Objects::nonNull).distinct().toList();
        if (topicIds.isEmpty()) return null;
        Map<Long, LearningTopic> byId = new HashMap<>(topics.findAllById(topicIds).stream()
                .collect(Collectors.toMap(LearningTopic::getId, Function.identity())));
        List<Long> missingRoots = byId.values().stream().map(LearningTopic::rootTopicId)
                .filter(root -> !byId.containsKey(root)).distinct().toList();
        if (!missingRoots.isEmpty()) topics.findAllById(missingRoots).forEach(topic -> byId.put(topic.getId(), topic));

        Set<Long> focus = focusTopicIds(path.getSurveyId());
        Map<Long, List<Long>> lessonsByRoot = new LinkedHashMap<>();
        for (Long lessonId : lessonIdsInPath) {
            LearningTopic topic = byId.get(topicByLesson.get(lessonId));
            if (topic == null) continue;
            lessonsByRoot.computeIfAbsent(topic.rootTopicId(), key -> new ArrayList<>()).add(lessonId);
        }
        List<CommunicationGuide.Topic> grouped = lessonsByRoot.entrySet().stream()
                .filter(entry -> byId.containsKey(entry.getKey()))
                .map(entry -> {
                    LearningTopic root = byId.get(entry.getKey());
                    List<CommunicationGuide.Unit> units = entry.getValue().stream()
                            .sorted(Comparator.comparingInt(lessonId -> order(byId.get(topicByLesson.get(lessonId)))))
                            .map(lessonId -> {
                                LearningTopic topic = byId.get(topicByLesson.get(lessonId));
                                boolean vocabulary = topic.getParentTopicId() == null;
                                return new CommunicationGuide.Unit(lessonId, vocabulary, vocabulary ? null : topic.getJlptLevel());
                            }).toList();
                    return new CommunicationGuide.Topic(root.getTopicCode(), root.getTitleVi(), order(root),
                            focus.contains(root.getId()), units);
                })
                .sorted(Comparator.comparing((CommunicationGuide.Topic topic) -> !topic.priority())
                        .thenComparingInt(CommunicationGuide.Topic::order))
                .toList();
        String selfLevel = path.getUser() == null ? null : surveys.findById(path.getUser().getId())
                .map(UserLearningSurvey::getSelfReportedLevel).orElse(null);
        return new CommunicationGuide(selfLevel, grouped, "BA".equalsIgnoreCase(path.getGoal()) ? brseBooks(selfLevel) : List.of());
    }

    /** Below N4 (or an unknown level): N4 + N3; N4/N3: N3; N2 and up: nothing is inferred. Optional, never gating. */
    private List<CommunicationGuide.BookSuggestion> brseBooks(String selfLevel) {
        String level = selfLevel == null ? "" : selfLevel.trim().toUpperCase(java.util.Locale.ROOT);
        List<String> wanted = switch (level) {
            case "N4", "N3" -> List.of("N3");
            case "N2", "N1" -> List.of();
            default -> List.of("N4", "N3");
        };
        List<CommunicationGuide.BookSuggestion> books = new ArrayList<>();
        if (wanted.contains("N4")) {
            textbooks.findBySourceCode(N4_BOOK_SOURCE_CODE).ifPresent(book -> books.add(suggestion("N4", book)));
        }
        if (wanted.contains("N3")) {
            textbooks.findAllByOrderByIdAsc().stream()
                    .filter(book -> "N3".equals(book.getJlptLevel()) && N3_BOOK_TITLE.equals(book.getTitle()))
                    .findFirst().ifPresent(book -> books.add(suggestion("N3", book)));
        }
        return books;
    }

    private static CommunicationGuide.BookSuggestion suggestion(String level, com.jlas.dictionary.entity.Textbook book) {
        // OD-45 (owner 2026-10-20): an owner-opened DRAFT textbook opens for learners but is never shown as reviewed.
        return new CommunicationGuide.BookSuggestion(level, book.getTitle(), book.getId(),
                com.jlas.common.learning.DraftStudySourcePolicy.isStudyableTextbook(book.getStatus(),
                        book.getIsLearningPathSource(), book.getJlptLevel()),
                !"PUBLISHED".equals(book.getStatus()));
    }

    /** Vocabulary topics the learner prioritised in the goal survey (submission id = path survey id). */
    public Set<Long> focusTopicIds(Long submissionId) {
        if (submissionId == null) return Set.of();
        List<Long> optionIds = selections.findBySubmissionId(submissionId).stream()
                .map(UserGoalSurveySelection::getOptionId).distinct().toList();
        return optionIds.isEmpty() ? Set.of() : options.findAllById(optionIds).stream()
                .map(LearningSurveyOption::getTopicId).filter(Objects::nonNull).collect(Collectors.toSet());
    }

    private static int order(LearningTopic topic) {
        return topic == null || topic.getTopicOrder() == null ? Integer.MAX_VALUE : topic.getTopicOrder();
    }
}
