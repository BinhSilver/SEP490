package com.jlas.learning.service.impl.engine;

import com.jlas.assessment.repository.AssessmentQuestionRepository;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.learning.dto.ComprehensionExercise;
import com.jlas.learning.enums.LearningActivityType;
import com.jlas.learning.enums.LearningMemberType;
import com.jlas.learning.service.ComprehensionExerciseCatalog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Reading/Listening strategy backed only by ready canonical assessment questions. */
public final class AssessmentComprehensionContentAdapter implements InteractiveContentAdapter<ComprehensionContent> {
    private final LearningActivityType type;
    private final AssessmentQuestionRepository questions;
    private final ComprehensionExerciseCatalog catalog;

    public AssessmentComprehensionContentAdapter(LearningActivityType type,
            AssessmentQuestionRepository questions, ComprehensionExerciseCatalog catalog) {
        if (type != LearningActivityType.READING && type != LearningActivityType.LISTENING) {
            throw new IllegalArgumentException("Comprehension adapter requires READING or LISTENING.");
        }
        this.type = type;
        this.questions = questions;
        this.catalog = catalog;
    }

    @Override public LearningActivityType activityType() { return type; }
    @Override public LearningMemberType memberType() { return LearningMemberType.COMPREHENSION_QUESTION; }
    @Override public String microCheckType() { return type.name() + "_MICRO_CHECK"; }
    @Override public boolean requiresServerReviewEvidence() { return true; }
    @Override public boolean requiresCanonicalPartitioning() { return true; }

    @Override
    public Map<Long, ComprehensionContent> loadByIds(List<Long> canonicalIds) {
        if (canonicalIds.isEmpty()) return Map.of();
        Set<Long> requested = new LinkedHashSet<>(canonicalIds);
        var rows = questions.findAllById(requested);
        Set<String> levels = new LinkedHashSet<>();
        rows.stream().filter(row -> requested.contains(row.getId()))
                .filter(row -> Boolean.TRUE.equals(row.getIsActive()) && type.name().equals(row.getSkill()))
                .forEach(row -> levels.add(row.getJlptLevel()));
        if (levels.size() != 1) return Map.of();
        var resolved = catalog.resolve(type.name(), levels.iterator().next());
        Map<Long, ComprehensionContent> result = new LinkedHashMap<>();
        for (ComprehensionExercise exercise : resolved.exercises()) {
            for (var question : exercise.questions()) {
                if (requested.contains(question.id())) {
                    result.put(question.id(), new ComprehensionContent(exercise, question));
                }
            }
        }
        return result;
    }

    @Override
    public List<List<ComprehensionContent>> partition(List<ComprehensionContent> orderedContent) {
        List<List<ComprehensionContent>> result = new ArrayList<>();
        String fingerprint = null;
        List<ComprehensionContent> current = null;
        Set<String> closed = new LinkedHashSet<>();
        for (ComprehensionContent content : orderedContent) {
            String next = content.exercise().fingerprint();
            if (!next.equals(fingerprint)) {
                if (closed.contains(next)) {
                    throw new ApiException(ErrorCode.BLOCKED_CONTENT, "Thứ tự nhóm bài đọc/nghe không hợp lệ.");
                }
                if (fingerprint != null) closed.add(fingerprint);
                fingerprint = next;
                current = new ArrayList<>();
                result.add(current);
            }
            current.add(content);
        }
        return result.stream().map(List::copyOf).toList();
    }

    @Override
    public List<QuestionSpec> buildQuestions(List<ComprehensionContent> batch, int batchIndex, int variationSeed) {
        return batch.stream().map(content -> new QuestionSpec(content.question().id(), "MULTIPLE_CHOICE",
                content.question().prompt(), content.question().options(), content.question().correctOption(),
                content.question().explanation())).toList();
    }

    @Override
    public String sourceSnapshotJson(List<ComprehensionContent> batch, int batchIndex, String sourceRevision) {
        String source = batch.getFirst().exercise().source();
        // Replay shows what the learner read: the passage with its paragraph breaks (not part of any revision).
        var frozen = type == LearningActivityType.READING
                ? new com.jlas.learning.dto.ComprehensionExerciseSource(batch.getFirst().exercise().displaySource(), null)
                : new com.jlas.learning.dto.ComprehensionExerciseSource(null, source, batch.getFirst().exercise().transcriptJa());
        return ComprehensionSourceSnapshot.toJson(type, batchIndex, sourceRevision, frozen);
    }

    @Override
    public void validateSourceRevision(String persistedRevision, List<ComprehensionContent> orderedContent) {
        List<ComprehensionExercise> exercises = new ArrayList<>();
        String last = null;
        for (ComprehensionContent content : orderedContent) {
            if (!content.exercise().fingerprint().equals(last)) exercises.add(content.exercise());
            last = content.exercise().fingerprint();
        }
        if (!ComprehensionRevision.combined(type, exercises).equals(persistedRevision)) {
            throw new ApiException(ErrorCode.BLOCKED_CONTENT,
                    "Nguồn bài đọc/nghe đã thay đổi. Hãy tạo lại lộ trình để dùng snapshot mới.");
        }
    }
}
