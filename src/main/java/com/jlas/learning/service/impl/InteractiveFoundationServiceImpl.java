package com.jlas.learning.service.impl;

import com.jlas.dictionary.entity.ContentItem;
import com.jlas.dictionary.repository.ContentItemRepository;
import com.jlas.learning.dto.FoundationStudyState;
import com.jlas.learning.dto.MicroCheckResponse;
import com.jlas.learning.dto.MicroCheckResult;
import com.jlas.learning.dto.MicroCheckStateResponse;
import com.jlas.learning.dto.MicroCheckSubmitRequest;
import com.jlas.learning.enums.LearningActivityType;
import com.jlas.learning.enums.LearningMemberType;
import com.jlas.learning.mapper.LearningMapper;
import com.jlas.learning.repository.LearningActivityMemberRepository;
import com.jlas.learning.repository.LearningMicroCheckStateRepository;
import com.jlas.learning.repository.LearningQuizSessionRepository;
import com.jlas.learning.repository.LearningStudyCardRepository;
import com.jlas.learning.repository.LearningStudySessionRepository;
import com.jlas.learning.repository.UserLearningPathItemRepository;
import com.jlas.learning.service.InteractiveFoundationService;
import com.jlas.learning.service.impl.engine.EngineStudyState;
import com.jlas.learning.service.impl.engine.InteractiveContentAdapter;
import com.jlas.learning.service.impl.engine.InteractiveStudyEngine;
import com.jlas.learning.service.impl.engine.QuestionSpec;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** [JLAS-W0108] Hiragana/Katakana foundation study + micro-check for "from-zero" (BELOW_N5) learners.
 * Reuses the exact Vocabulary/Kanji contract — flip-before-remember with resume, immutable snapshot,
 * server-owned eligibility/score/attempt-cycle/cooldown/review, 70% pass, 3 valid submits per cycle,
 * idempotent submit — via the shared {@link InteractiveStudyEngine}. Canonical content is a
 * {@code content_item} row (content_type=READING) under a kana chart lesson: {@code title} = romaji,
 * {@code content} = the kana character itself. No dedicated table — this reuses the existing digitized
 * kana chart content (textbook "Bảng chữ cái tiếng Nhật") verbatim. */
@Service
public class InteractiveFoundationServiceImpl implements InteractiveFoundationService {
    static final String MICRO_CHECK_TYPE = "FOUNDATION_MICRO_CHECK";
    private static final String[] SELECT_TYPES = {"CHARACTER_TO_ROMAJI", "ROMAJI_TO_CHARACTER"};

    private final InteractiveStudyEngine<ContentItem> engine;

    public InteractiveFoundationServiceImpl(UserLearningPathItemRepository items,
            LearningActivityMemberRepository members, LearningStudySessionRepository studySessions,
            LearningStudyCardRepository studyCards, LearningMicroCheckStateRepository checkStates,
            LearningQuizSessionRepository quizSessions, ContentItemRepository contentItems, LearningMapper mapper) {
        this.engine = new InteractiveStudyEngine<>(items, members, studySessions, studyCards, checkStates,
                quizSessions, mapper, new FoundationContentAdapter(contentItems));
    }

    @Override
    @Transactional
    public FoundationStudyState studyState(Long userId, Long activityId) {
        return toDto(engine.studyState(userId, activityId));
    }

    @Override
    @Transactional
    public FoundationStudyState flip(Long userId, Long activityId, Long memberId) {
        return toDto(engine.flip(userId, activityId, memberId));
    }

    @Override
    @Transactional
    public FoundationStudyState remember(Long userId, Long activityId, Long memberId, boolean remembered) {
        return toDto(engine.remember(userId, activityId, memberId, remembered));
    }

    @Override
    @Transactional
    public MicroCheckResponse issueMicroCheck(Long userId, Long activityId) {
        return engine.issueMicroCheck(userId, activityId);
    }

    @Override
    @Transactional
    public MicroCheckResult submitMicroCheck(Long userId, Long activityId, MicroCheckSubmitRequest request) {
        return engine.submitMicroCheck(userId, activityId, request);
    }

    @Override
    @Transactional
    public MicroCheckStateResponse microCheckState(Long userId, Long activityId) {
        return engine.microCheckState(userId, activityId);
    }

    @Override
    @Transactional
    public MicroCheckStateResponse completeReview(Long userId, Long activityId) {
        return engine.completeReview(userId, activityId);
    }

    @Override
    @Transactional(readOnly = true)
    public FoundationStudyState reviewState(Long userId, Long activityId) {
        return toDto(engine.reviewState(userId, activityId));
    }

    @Override
    @Transactional
    public MicroCheckResponse issuePractice(Long userId, Long activityId) {
        return engine.issuePractice(userId, activityId);
    }

    @Override
    @Transactional
    public MicroCheckResult submitPractice(Long userId, Long activityId, MicroCheckSubmitRequest request) {
        return engine.submitPractice(userId, activityId, request);
    }

    private FoundationStudyState toDto(EngineStudyState<ContentItem> s) {
        List<FoundationStudyState.FoundationStudyCard> cards = s.cards().stream().map(c -> {
            ContentItem ci = c.entity();
            return new FoundationStudyState.FoundationStudyCard(c.memberId(), c.orderIndex(),
                    ci == null ? null : ci.getContent(), ci == null ? null : ci.getTitle(),
                    c.flipped(), c.remembered());
        }).toList();
        return new FoundationStudyState(s.activityId(), s.activityType(), s.activityState(), s.sourceRevision(),
                s.totalCards(), s.flippedCount(), s.rememberedCount(), s.reviewRemaining(), s.checkEligible(), cards);
    }

    /** Foundation-specific seam: canonical loading (content_item) + deterministic, no-AI question
     * generation across the two available shapes (character→romaji, romaji→character). */
    private static final class FoundationContentAdapter implements InteractiveContentAdapter<ContentItem> {
        private final ContentItemRepository contentItems;

        FoundationContentAdapter(ContentItemRepository contentItems) {
            this.contentItems = contentItems;
        }

        @Override
        public LearningActivityType activityType() {
            return LearningActivityType.FOUNDATION;
        }

        @Override
        public LearningMemberType memberType() {
            return LearningMemberType.FOUNDATION_CHARACTER;
        }

        @Override
        public String microCheckType() {
            return MICRO_CHECK_TYPE;
        }

        @Override
        public Map<Long, ContentItem> loadByIds(List<Long> canonicalIds) {
            return contentItems.findAllById(canonicalIds).stream()
                    .collect(java.util.stream.Collectors.toMap(ContentItem::getId, ci -> ci));
        }

        @Override
        public List<QuestionSpec> buildQuestions(List<ContentItem> batch, int batchIndex, int variationSeed) {
            List<QuestionSpec> specs = new ArrayList<>();
            long qid = 1;
            for (int i = 0; i < batch.size(); i++) {
                ContentItem e = batch.get(i);
                if (e.getContent() == null || e.getTitle() == null) continue; // no usable field (should not happen with approved content)
                String type = SELECT_TYPES[(i + variationSeed) % SELECT_TYPES.length];
                specs.add(optionQuestion(qid++, i + variationSeed, type, selectPrompt(type, e), selectCorrect(type, e),
                        selectDistractors(type, e, batch)));
            }
            return specs;
        }

        private static String selectPrompt(String type, ContentItem e) {
            return switch (type) {
                case "CHARACTER_TO_ROMAJI" -> "Chữ “" + e.getContent() + "” đọc là gì?";
                case "ROMAJI_TO_CHARACTER" -> "Chữ nào đọc là “" + e.getTitle() + "”?";
                default -> throw new IllegalArgumentException(type);
            };
        }

        private static String selectCorrect(String type, ContentItem e) {
            return "CHARACTER_TO_ROMAJI".equals(type) ? e.getTitle() : e.getContent();
        }

        private static List<String> selectDistractors(String type, ContentItem e, List<ContentItem> batch) {
            return "CHARACTER_TO_ROMAJI".equals(type)
                    ? batch.stream().map(ContentItem::getTitle).toList()
                    : batch.stream().map(ContentItem::getContent).toList();
        }

        private static QuestionSpec optionQuestion(long questionId, int rotateSeed, String type, String prompt,
                String correctValue, List<String> distractorValues) {
            LinkedHashSet<String> set = new LinkedHashSet<>();
            if (correctValue != null) set.add(correctValue);
            for (String d : distractorValues) {
                if (d != null && !d.isEmpty() && !d.equals(correctValue)) set.add(d);
            }
            List<String> options = new ArrayList<>(set);
            int size = Math.min(4, options.size());
            options = new ArrayList<>(options.subList(0, size));
            int rotate = rotateSeed % size;
            List<String> rotated = new ArrayList<>(size);
            for (int k = 0; k < size; k++) rotated.add(options.get((k + rotate) % size));
            int correctOption = (options.indexOf(correctValue) - rotate + size) % size + 1;
            return new QuestionSpec(questionId, type, prompt, rotated, correctOption, correctValue);
        }
    }
}
