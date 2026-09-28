package com.jlas.learning.service.impl;

import com.jlas.dictionary.entity.Kanji;
import com.jlas.dictionary.entity.KanjiReading;
import com.jlas.dictionary.repository.KanjiReadingRepository;
import com.jlas.dictionary.repository.KanjiRepository;
import com.jlas.learning.dto.KanjiStudyState;
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
import com.jlas.learning.service.InteractiveKanjiService;
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

/** [JLAS-W0193] Task 5 — Kanji study + micro-check. Reuses the exact Vocabulary (Task 4) contract — flip-before-
 * remember with resume, immutable snapshot, server-owned eligibility/score/attempt-cycle/cooldown/review, 70%
 * pass, 3 valid submits per cycle, idempotent submit — via the shared {@link InteractiveStudyEngine}. This class
 * only supplies the Kanji-specific content adapter (canonical loading + question generation) and maps the
 * engine's generic study state into {@link KanjiStudyState}. Kanji score never compensates or overwrites
 * Vocabulary's score — they are separate activities with separate {@link com.jlas.learning.entity.LearningMicroCheckState} rows. */
@Service
public class InteractiveKanjiServiceImpl implements InteractiveKanjiService {
    static final String MICRO_CHECK_TYPE = "KANJI_MICRO_CHECK";
    private static final String[] SELECT_TYPES = {"KANJI_TO_READING", "READING_TO_KANJI", "KANJI_TO_MEANING"};

    private final InteractiveStudyEngine<KanjiEntry> engine;

    public InteractiveKanjiServiceImpl(UserLearningPathItemRepository items, LearningActivityMemberRepository members,
            LearningStudySessionRepository studySessions, LearningStudyCardRepository studyCards,
            LearningMicroCheckStateRepository checkStates, LearningQuizSessionRepository quizSessions,
            KanjiRepository kanjiRepo, KanjiReadingRepository kanjiReadingRepo, LearningMapper mapper) {
        this.engine = new InteractiveStudyEngine<>(items, members, studySessions, studyCards, checkStates,
                quizSessions, mapper, new KanjiContentAdapter(kanjiRepo, kanjiReadingRepo));
    }

    @Override
    @Transactional
    public KanjiStudyState studyState(Long userId, Long activityId) {
        return toDto(engine.studyState(userId, activityId));
    }

    @Override
    @Transactional
    public KanjiStudyState flip(Long userId, Long activityId, Long memberId) {
        return toDto(engine.flip(userId, activityId, memberId));
    }

    @Override
    @Transactional
    public KanjiStudyState remember(Long userId, Long activityId, Long memberId, boolean remembered) {
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

    private KanjiStudyState toDto(EngineStudyState<KanjiEntry> s) {
        List<KanjiStudyState.KanjiStudyCard> cards = s.cards().stream().map(c -> {
            KanjiEntry e = c.entity();
            Kanji k = e == null ? null : e.kanji();
            return new KanjiStudyState.KanjiStudyCard(c.memberId(), c.orderIndex(),
                    k == null ? null : k.getCharacter(), k == null ? null : k.getHanViet(),
                    k == null ? null : k.getMeaningVi(), k == null ? null : k.getJlptLevel(),
                    k == null || k.getStrokeCount() == null ? null : k.getStrokeCount().intValue(),
                    e == null ? List.of() : e.onReadings(), e == null ? List.of() : e.kunReadings(),
                    c.flipped(), c.remembered());
        }).toList();
        return new KanjiStudyState(s.activityId(), s.activityType(), s.activityState(), s.sourceRevision(),
                s.totalCards(), s.flippedCount(), s.rememberedCount(), s.reviewRemaining(), s.checkEligible(), cards);
    }

    /** Canonical Kanji + its On/Kun readings, loaded once per batch — never fabricated when absent. */
    private record KanjiEntry(Kanji kanji, List<String> onReadings, List<String> kunReadings) {
        String primaryReading() {
            if (!onReadings.isEmpty()) return onReadings.get(0);
            if (!kunReadings.isEmpty()) return kunReadings.get(0);
            return null;
        }
    }

    /** Kanji-specific seam: canonical loading (Kanji + KanjiReading) + deterministic, no-AI question generation
     * across the four required shapes (Kanji→reading, reading→Kanji, Kanji→meaning, matching). */
    private static final class KanjiContentAdapter implements InteractiveContentAdapter<KanjiEntry> {
        private final KanjiRepository kanjiRepo;
        private final KanjiReadingRepository kanjiReadingRepo;

        KanjiContentAdapter(KanjiRepository kanjiRepo, KanjiReadingRepository kanjiReadingRepo) {
            this.kanjiRepo = kanjiRepo;
            this.kanjiReadingRepo = kanjiReadingRepo;
        }

        @Override
        public LearningActivityType activityType() {
            return LearningActivityType.KANJI;
        }

        @Override
        public LearningMemberType memberType() {
            return LearningMemberType.KANJI;
        }

        @Override
        public String microCheckType() {
            return MICRO_CHECK_TYPE;
        }

        @Override
        public Map<Long, KanjiEntry> loadByIds(List<Long> canonicalIds) {
            List<Kanji> kanjiList = kanjiRepo.findAllById(canonicalIds);
            List<KanjiReading> readings = kanjiReadingRepo
                    .findByKanjiIdInOrderByKanjiIdAscReadingTypeAscIdAsc(canonicalIds);
            Map<Long, List<KanjiReading>> byKanjiId = readings.stream()
                    .collect(Collectors.groupingBy(r -> r.getKanji().getId()));
            return kanjiList.stream().collect(Collectors.toMap(Kanji::getId, k -> {
                List<KanjiReading> forKanji = byKanjiId.getOrDefault(k.getId(), List.of());
                List<String> on = forKanji.stream().filter(r -> "ON".equalsIgnoreCase(r.getReadingType()))
                        .map(KanjiReading::getReadingText).toList();
                List<String> kun = forKanji.stream().filter(r -> "KUN".equalsIgnoreCase(r.getReadingType()))
                        .map(KanjiReading::getReadingText).toList();
                return new KanjiEntry(k, on, kun);
            }));
        }

        @Override
        public List<QuestionSpec> buildQuestions(List<KanjiEntry> batch, int batchIndex) {
            List<QuestionSpec> specs = new ArrayList<>();
            long qid = 1;
            for (int i = 0; i < batch.size(); i++) {
                KanjiEntry e = batch.get(i);
                String type = pickAvailableType(e, i);
                if (type == null) continue; // no usable field at all (should not happen with approved data)
                specs.add(optionQuestion(qid++, i, type, selectPrompt(type, e), selectCorrect(type, e),
                        selectDistractors(type, e, batch)));
            }
            for (int i = 0; i < batch.size(); i++) {
                KanjiEntry e = batch.get(i);
                if (e.kanji().getMeaningVi() == null) continue;
                specs.add(optionQuestion(qid++, i, "MATCHING",
                        "Ghép chữ “" + e.kanji().getCharacter() + "” với nghĩa đúng.",
                        e.kanji().getMeaningVi(), batch.stream().map(b -> b.kanji().getMeaningVi()).toList()));
            }
            return specs;
        }

        /** Reading-based question types need a real reading; falls back to Kanji→meaning (always populated
         * for approved content) rather than fabricating a reading. */
        private static String pickAvailableType(KanjiEntry e, int rotateSeed) {
            String preferred = SELECT_TYPES[rotateSeed % SELECT_TYPES.length];
            boolean needsReading = !"KANJI_TO_MEANING".equals(preferred);
            if (needsReading && e.primaryReading() == null) {
                return e.kanji().getMeaningVi() != null ? "KANJI_TO_MEANING" : null;
            }
            if ("KANJI_TO_MEANING".equals(preferred) && e.kanji().getMeaningVi() == null) {
                return e.primaryReading() != null ? "KANJI_TO_READING" : null;
            }
            return preferred;
        }

        private static String selectPrompt(String type, KanjiEntry e) {
            Kanji k = e.kanji();
            return switch (type) {
                case "KANJI_TO_READING" -> "Chữ “" + k.getCharacter() + "” đọc là gì?";
                case "READING_TO_KANJI" -> "Chữ nào đọc là “" + e.primaryReading() + "”?";
                case "KANJI_TO_MEANING" -> "Chữ “" + k.getCharacter() + "” có nghĩa là gì?";
                default -> throw new IllegalArgumentException(type);
            };
        }

        private static String selectCorrect(String type, KanjiEntry e) {
            return switch (type) {
                case "KANJI_TO_READING" -> e.primaryReading();
                case "READING_TO_KANJI", "KANJI_TO_MEANING" -> type.equals("READING_TO_KANJI")
                        ? e.kanji().getCharacter() : e.kanji().getMeaningVi();
                default -> throw new IllegalArgumentException(type);
            };
        }

        private static List<String> selectDistractors(String type, KanjiEntry e, List<KanjiEntry> batch) {
            return switch (type) {
                case "KANJI_TO_READING" -> batch.stream().map(KanjiEntry::primaryReading).toList();
                case "READING_TO_KANJI" -> batch.stream().map(b -> b.kanji().getCharacter()).toList();
                case "KANJI_TO_MEANING" -> batch.stream().map(b -> b.kanji().getMeaningVi()).toList();
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
            int correctOption = (options.indexOf(correctValue) - rotate + size) % size + 1;
            return new QuestionSpec(questionId, type, prompt, rotated, correctOption, correctValue);
        }
    }
}
