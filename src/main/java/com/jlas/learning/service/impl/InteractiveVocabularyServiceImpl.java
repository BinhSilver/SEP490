package com.jlas.learning.service.impl;

import com.jlas.dictionary.entity.VocabularyEntry;
import com.jlas.dictionary.repository.VocabularyEntryRepository;
import com.jlas.learning.dto.MicroCheckResponse;
import com.jlas.learning.dto.MicroCheckResult;
import com.jlas.learning.dto.MicroCheckStateResponse;
import com.jlas.learning.dto.MicroCheckSubmitRequest;
import com.jlas.learning.dto.VocabularyStudyState;
import com.jlas.learning.enums.LearningActivityType;
import com.jlas.learning.enums.LearningMemberType;
import com.jlas.learning.mapper.LearningMapper;
import com.jlas.learning.repository.LearningActivityMemberRepository;
import com.jlas.learning.repository.LearningMicroCheckStateRepository;
import com.jlas.learning.repository.LearningQuizSessionRepository;
import com.jlas.learning.repository.LearningStudyCardRepository;
import com.jlas.learning.repository.LearningStudySessionRepository;
import com.jlas.learning.repository.UserLearningPathItemRepository;
import com.jlas.learning.service.InteractiveVocabularyService;
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
import java.util.stream.Collectors;

/** [JLAS-W0193] Vocabulary study + micro-check. Every mutating method locks the owned activity row first so
 * concurrent tabs cannot double-spend attempts or duplicate a study session. The state machine itself lives in
 * {@link InteractiveStudyEngine} (extracted in Task 5 so Kanji can reuse it); this class only supplies the
 * Vocabulary-specific content adapter and maps the engine's generic study state into {@link VocabularyStudyState}. */
@Service
public class InteractiveVocabularyServiceImpl implements InteractiveVocabularyService {
    static final String MICRO_CHECK_TYPE = "VOCAB_MICRO_CHECK";
    private static final String[] SELECT_TYPES = {"WORD_TO_MEANING", "READING_TO_WORD", "MEANING_TO_WORD"};

    private final InteractiveStudyEngine<VocabularyEntry> engine;

    public InteractiveVocabularyServiceImpl(UserLearningPathItemRepository items, LearningActivityMemberRepository members,
            LearningStudySessionRepository studySessions, LearningStudyCardRepository studyCards,
            LearningMicroCheckStateRepository checkStates, LearningQuizSessionRepository quizSessions,
            VocabularyEntryRepository vocabularyRepo, LearningMapper mapper) {
        this.engine = new InteractiveStudyEngine<>(items, members, studySessions, studyCards, checkStates,
                quizSessions, mapper, new VocabularyContentAdapter(vocabularyRepo));
    }

    @Override
    @Transactional
    public VocabularyStudyState studyState(Long userId, Long activityId) {
        return toDto(engine.studyState(userId, activityId));
    }

    @Override
    @Transactional
    public VocabularyStudyState flip(Long userId, Long activityId, Long memberId) {
        return toDto(engine.flip(userId, activityId, memberId));
    }

    @Override
    @Transactional
    public VocabularyStudyState remember(Long userId, Long activityId, Long memberId, boolean remembered) {
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
    public VocabularyStudyState reviewState(Long userId, Long activityId) {
        return toDto(engine.reviewState(userId, activityId));
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.List<com.jlas.learning.dto.AttemptFeedbackResponse> reviewFeedback(Long userId, Long activityId) {
        return engine.reviewFeedback(userId, activityId);
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

    private VocabularyStudyState toDto(EngineStudyState<VocabularyEntry> s) {
        List<VocabularyStudyState.VocabularyStudyCard> cards = s.cards().stream().map(c -> {
            VocabularyEntry e = c.entity();
            return new VocabularyStudyState.VocabularyStudyCard(c.memberId(), c.orderIndex(),
                    value(e, VocabularyEntry::getExpression), value(e, VocabularyEntry::getReading),
                    value(e, VocabularyEntry::getMeaning), value(e, VocabularyEntry::getWordType),
                    value(e, VocabularyEntry::getJlptLevel), value(e, VocabularyEntry::getExampleJa),
                    value(e, VocabularyEntry::getExampleVi), c.flipped(), c.remembered());
        }).toList();
        return new VocabularyStudyState(s.activityId(), s.activityType(), s.activityState(), s.sourceRevision(),
                s.totalCards(), s.flippedCount(), s.rememberedCount(), s.reviewRemaining(), s.checkEligible(), cards);
    }

    private static String value(VocabularyEntry entry, java.util.function.Function<VocabularyEntry, String> getter) {
        return entry == null ? null : getter.apply(entry);
    }

    /** Vocabulary-specific seam: canonical loading + deterministic, no-AI question generation. */
    private static final class VocabularyContentAdapter implements InteractiveContentAdapter<VocabularyEntry> {
        private final VocabularyEntryRepository vocabularyRepo;

        VocabularyContentAdapter(VocabularyEntryRepository vocabularyRepo) {
            this.vocabularyRepo = vocabularyRepo;
        }

        @Override
        public LearningActivityType activityType() {
            return LearningActivityType.VOCABULARY;
        }

        @Override
        public LearningMemberType memberType() {
            return LearningMemberType.VOCABULARY_ENTRY;
        }

        @Override
        public String microCheckType() {
            return MICRO_CHECK_TYPE;
        }

        @Override
        public Map<Long, VocabularyEntry> loadByIds(List<Long> canonicalIds) {
            return vocabularyRepo.findAllById(canonicalIds).stream()
                    .collect(Collectors.toMap(VocabularyEntry::getId, v -> v));
        }

        @Override
        public List<QuestionSpec> buildQuestions(List<VocabularyEntry> batch, int batchIndex, int variationSeed) {
            List<QuestionSpec> specs = new ArrayList<>();
            long qid = 1;
            for (int i = 0; i < batch.size(); i++) {
                VocabularyEntry m = batch.get(i);
                String type = SELECT_TYPES[(i + variationSeed) % SELECT_TYPES.length];
                specs.add(optionQuestion(qid++, i + variationSeed, type, selectPrompt(type, m), selectCorrect(type, m),
                        selectDistractors(type, m, batch)));
            }
            for (int i = 0; i < batch.size(); i++) {
                VocabularyEntry m = batch.get(i);
                specs.add(optionQuestion(qid++, i + variationSeed, "MATCHING",
                        "Ghép từ “" + m.getExpression() + "” với nghĩa đúng.", m.getMeaning(),
                        batch.stream().map(VocabularyEntry::getMeaning).toList()));
            }
            return specs;
        }

        private static String selectPrompt(String type, VocabularyEntry m) {
            return switch (type) {
                case "WORD_TO_MEANING" -> "Từ “" + m.getExpression() + "” có nghĩa là gì?";
                case "READING_TO_WORD" -> "Từ nào có cách đọc “" + m.getReading() + "”?";
                case "MEANING_TO_WORD" -> "Từ nào có nghĩa “" + m.getMeaning() + "”?";
                default -> throw new IllegalArgumentException(type);
            };
        }

        private static String selectCorrect(String type, VocabularyEntry m) {
            return switch (type) {
                case "WORD_TO_MEANING" -> m.getMeaning();
                case "READING_TO_WORD", "MEANING_TO_WORD" -> m.getExpression();
                default -> throw new IllegalArgumentException(type);
            };
        }

        private static List<String> selectDistractors(String type, VocabularyEntry m, List<VocabularyEntry> batch) {
            return switch (type) {
                case "WORD_TO_MEANING" -> batch.stream().map(VocabularyEntry::getMeaning).toList();
                case "READING_TO_WORD", "MEANING_TO_WORD" -> batch.stream().map(VocabularyEntry::getExpression).toList();
                default -> throw new IllegalArgumentException(type);
            };
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
            // 1-based option index, matching the rest of the quiz system (@Min(1) submit + LessonQuiz grading).
            int correctOption = (options.indexOf(correctValue) - rotate + size) % size + 1;
            return new QuestionSpec(questionId, type, prompt, rotated, correctOption, correctValue);
        }
    }
}
