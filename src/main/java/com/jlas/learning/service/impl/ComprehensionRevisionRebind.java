package com.jlas.learning.service.impl;

import com.jlas.learning.dto.ComprehensionQuestionSpec;
import com.jlas.learning.dto.LessonQuizResponse;
import com.jlas.learning.dto.QuizAnswerKey;
import com.jlas.learning.entity.LearningActivityMember;
import com.jlas.learning.entity.LearningQuizSession;
import com.jlas.learning.entity.UserLearningPathItem;
import com.jlas.learning.mapper.LearningMapper;
import com.jlas.learning.repository.LearningActivityMemberRepository;
import com.jlas.learning.repository.LearningQuizSessionRepository;
import com.jlas.learning.service.impl.engine.ComprehensionContent;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * OD-D2-04 (owner 2026-10-23): a Reading/Listening source edited after a path was made moves the activity to the new
 * revision when nothing the learner answered changed — same prompt, options and key, ignoring whitespace. Past attempts
 * keep their own frozen snapshot, so scores and saved answers stay as they were. A question that really changed
 * under an answered attempt keeps the activity on its old revision (still blocked), never silently re-scored.
 */
@Slf4j
final class ComprehensionRevisionRebind {
    private final LearningActivityMemberRepository members;
    private final LearningQuizSessionRepository quizSessions;
    private final LearningMapper mapper;

    ComprehensionRevisionRebind(LearningActivityMemberRepository members, LearningQuizSessionRepository quizSessions,
            LearningMapper mapper) {
        this.members = members;
        this.quizSessions = quizSessions;
        this.mapper = mapper;
    }

    /** @return true when the membership now carries {@code currentRevision}. */
    boolean rebindIfSafe(UserLearningPathItem activity, List<LearningActivityMember> membership,
            List<ComprehensionContent> ordered, String currentRevision) {
        if (membership.isEmpty() || currentRevision == null) return false;
        if (membership.stream().allMatch(member -> currentRevision.equals(member.getSourceRevision()))) return true;
        Map<Long, ComprehensionQuestionSpec> now = new HashMap<>();
        ordered.forEach(content -> now.put(content.question().id(), content.question()));
        for (LearningQuizSession attempt : quizSessions.findByActivityIdOrderByIdAsc(activity.getId())) {
            if (attempt.getQuestionsJson() == null) continue;
            Map<Long, QuizAnswerKey> key = attempt.getAnswerKeyJson() == null ? Map.of()
                    : mapper.readQuizAnswerKey(attempt.getAnswerKeyJson());
            for (LessonQuizResponse.LessonQuizQuestion asked : mapper.readQuizQuestions(attempt.getQuestionsJson())) {
                if (!sameQuestion(asked, key.get(asked.questionId()), now.get(asked.questionId()))) {
                    log.warn("Activity {} keeps its revision: question {} changed under attempt {}",
                            activity.getId(), asked.questionId(), attempt.getId());
                    return false;
                }
            }
        }
        membership.forEach(member -> member.setSourceRevision(currentRevision));
        members.saveAll(membership);
        log.info("Activity {} moved to the current source revision (only explanations/spacing changed)", activity.getId());
        return true;
    }

    static boolean sameQuestion(LessonQuizResponse.LessonQuizQuestion asked, QuizAnswerKey key, ComprehensionQuestionSpec now) {
        if (now == null || !compact(asked.questionText()).equals(compact(now.prompt()))) return false;
        List<String> askedOptions = asked.options().stream().map(ComprehensionRevisionRebind::compact).sorted().toList();
        List<String> nowOptions = now.options().stream().map(ComprehensionRevisionRebind::compact).sorted().toList();
        if (!askedOptions.equals(nowOptions)) return false;
        if (key == null) return true;
        int askedIndex = key.correctOption() - 1;
        int nowIndex = now.correctOption() - 1;
        if (askedIndex < 0 || askedIndex >= asked.options().size() || nowIndex < 0 || nowIndex >= now.options().size()) {
            return false;
        }
        return Objects.equals(compact(asked.options().get(askedIndex)), compact(now.options().get(nowIndex)));
    }

    /** Whitespace only: the furigana-stripping artefact "来 ました" and "来ました" are the same sentence. */
    static String compact(String text) {
        return text == null ? "" : text.replaceAll("[\\s\\u3000]+", "");
    }
}
