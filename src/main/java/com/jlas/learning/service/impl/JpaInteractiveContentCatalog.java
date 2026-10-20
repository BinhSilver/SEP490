package com.jlas.learning.service.impl;

import com.jlas.common.exception.*;
import com.jlas.common.learning.DraftStudySourcePolicy;
import com.jlas.dictionary.entity.Lesson;
import com.jlas.dictionary.entity.VocabularyEntry;
import com.jlas.dictionary.repository.*;
import com.jlas.assessment.entity.AssessmentQuestion;
import com.jlas.assessment.repository.AssessmentQuestionRepository;
import com.jlas.learning.dto.*;
import com.jlas.learning.service.InteractiveContentCatalog;
import com.jlas.learning.dto.ReferenceVocabularyResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Collectors;

@Component @RequiredArgsConstructor @Transactional(readOnly = true)
public class JpaInteractiveContentCatalog implements InteractiveContentCatalog {
    private final LessonRepository lessons; private final ContentVocabularyRepository contentVocabulary;
    private final VocabularyKanjiRepository vocabularyKanji; private final KanjiReadingRepository kanjiReadings;
    private final GrammarPointRepository grammarPoints; private final ContentItemRepository contentItems;
    private final DialogueLineDetailRepository dialogueLines; private final ContentAudioRepository audio;
    private final AssessmentQuestionRepository assessmentQuestions;

    @Override public InteractiveContentSnapshot inspect(Long lessonId, String expectedLevel) {
        var lesson=lessons.findWithUnitById(lessonId).orElseThrow(()->new ApiException(ErrorCode.NOT_FOUND,"Không tìm thấy bài nguồn."));
        var gaps=new ArrayList<CoverageGap>(); String key="LESSON:"+lessonId;
        String actualLevel=lesson.getUnit().getTextbook().getJlptLevel();
        var textbook=lesson.getUnit().getTextbook();
        // §2.28 / OD-44: an owner-opened DRAFT learning-path book is studied as it is; its statuses never change.
        boolean openDraft=DraftStudySourcePolicy.isOpenDraftTextbook(textbook.getStatus(),textbook.getIsLearningPathSource(),textbook.getJlptLevel());
        if(!DraftStudySourcePolicy.isStudyableLesson(lesson.getStatus(),textbook.getStatus(),textbook.getIsLearningPathSource(),textbook.getJlptLevel())) gap(gaps,key,"SOURCE_NOT_PUBLISHED");
        if (!Boolean.TRUE.equals(lesson.getUnit().getTextbook().getIsLearningPathSource())) gap(gaps,key,"SOURCE_NOT_LEARNING_PATH");
        // JLPT stages must match their textbook exactly. Goal-specific communication stages
        // (BASIC/FUNCTIONAL/PROBLEM_SOLVING/FLEXIBLE) are validated by APPROVED lesson scope,
        // not by overloading textbook.jlpt_level with a non-JLPT value.
        if(isJlptLevel(expectedLevel)&&!Objects.equals(expectedLevel,actualLevel)) gap(gaps,key,"LEVEL_MISMATCH");
        List<VocabularyEntry> vocab=contentVocabulary.findVocabularyByLessonId(lessonId);
        if(vocab.stream().map(v->v.getNormalizedExpression()+"\0"+v.getNormalizedReading()).distinct().count()!=vocab.size()) gap(gaps,key,"DUPLICATE_VOCABULARY_IDENTITY");
        List<Long> vocabIds=vocab.stream().map(VocabularyEntry::getId).distinct().sorted().toList();
        Map<Long, VocabularyLessonContext> contexts = new TreeMap<>();
        var contextualLinks = contentVocabulary.findLearningContextsByLessonId(lessonId);
        if (contextualLinks != null) for (var link : contextualLinks) {
            Long vocabularyId = link.getVocabularyEntry().getId();
            if (!vocabIds.contains(vocabularyId) || blank(link.getLearningMeaningVi())) {
                gap(gaps,key,"VOCABULARY_CONTEXT_NOT_READY");
                continue;
            }
            var context = new VocabularyLessonContext(link.getLearningMeaningVi(),
                    link.getLearningExampleJa(), link.getLearningExampleVi());
            var prior = contexts.putIfAbsent(vocabularyId, context);
            if (prior != null && !prior.equals(context)) gap(gaps,key,"VOCABULARY_CONTEXT_CONFLICT");
        }
        String sourceCode = lesson.getUnit().getTextbook().getSourceCode();
        boolean ownedLesson = sourceCode != null
                && (sourceCode.startsWith("LP-OWNED-") || sourceCode.startsWith("LP-REVIEWED-"));
        if (ownedLesson && contextualLinks != null && !contextualLinks.isEmpty()) {
            List<Long> sourceOrder = contextualLinks.stream().map(link -> link.getVocabularyEntry().getId())
                    .distinct().toList();
            if (sourceOrder.size() == vocabIds.size() && sourceOrder.containsAll(vocabIds)) {
                vocabIds = sourceOrder;
            } else gap(gaps,key,"VOCABULARY_CONTEXT_NOT_READY");
        }
        if(vocab.stream().anyMatch(v -> blank(v.getReading())
                || (!("PUBLISHED".equals(v.getDictionaryStatus()) && !blank(v.getMeaning()))
                    && !(ownedLesson && "DRAFT".equals(v.getDictionaryStatus()) && contexts.containsKey(v.getId())))))
            gap(gaps,key,"VOCABULARY_NOT_READY");
        var links=vocabIds.isEmpty()?List.<com.jlas.dictionary.entity.VocabularyKanji>of():vocabularyKanji.findByVocabularyIdIn(vocabIds);
        if (openDraft) links=links.stream()
                .filter(link->DraftStudySourcePolicy.isKanjiWithinStage(link.getKanji().getJlptLevel(),textbook.getJlptLevel())).toList();
        List<Long> kanjiIds=links.stream().map(v->v.getKanji().getId()).distinct().sorted().toList();
        if (!kanjiIds.isEmpty()) {
            if(links.stream().anyMatch(link->blank(link.getKanji().getCharacter())||(blank(link.getKanji().getMeaningVi())&&blank(link.getKanji().getHanViet())))) gap(gaps,key,"KANJI_NOT_READY");
            var covered=kanjiReadings.findByKanjiIdInOrderByKanjiIdAscReadingTypeAscIdAsc(kanjiIds).stream().map(r->r.getKanji().getId()).collect(Collectors.toSet());
            if(!covered.containsAll(kanjiIds)) gap(gaps,key,"KANJI_READING_NOT_READY");
        }
        var grammar=grammarPoints.findByLessonIdOrderByOrderIndexAsc(lessonId); List<Long> grammarIds=grammar.stream().map(g->g.getId()).distinct().toList();
        if(grammarIds.size()>12) gap(gaps,key,"TOO_MANY_GRAMMAR_POINTS");
        if(grammar.stream().anyMatch(g->blank(g.getPattern())||blank(g.getMeaning())||(blank(g.getExample())&&contentItems.findGrammarExamples(g.getId(),lessonId).isEmpty()))) gap(gaps,key,"GRAMMAR_EXAMPLE_NOT_READY");
        var dialogue=dialogueLines.findByLessonId(lessonId); List<Long> dialogueIds=dialogue.stream().map(d->d.getContentItemId()).distinct().toList();
        // Open-draft dialogues have no recorded audio yet; the player uses the labelled synthesized voice instead.
        if(!dialogueIds.isEmpty() && !openDraft) {
            var audioOwners=audio.findByOwnerTypeAndOwnerIdIn("CONTENT_ITEM",dialogueIds).stream().filter(a->!blank(a.getAudioUrl())).map(a->a.getOwnerId()).collect(Collectors.toSet());
            if(!audioOwners.containsAll(dialogueIds)) gap(gaps,key,"DIALOGUE_AUDIO_NOT_READY");
        }
        if(dialogue.stream().anyMatch(d->blank(d.getContentItem().getContent()))) gap(gaps,key,"DIALOGUE_SCRIPT_NOT_READY");
        List<Long> readingIds = ownedMinnaReadingIds(lesson, expectedLevel, gaps, key);
        if (readingIds.isEmpty() && "N5".equals(expectedLevel)) {
            // Preserve existing N5 paths until the Learning-owned Minna reading bundle is applied.
            readingIds = ownedSekaiComprehensionIds(lesson, expectedLevel, "READING", gaps, key);
        }
        List<Long> listeningIds = ownedMinnaListeningIds(lesson, expectedLevel, gaps, key);
        if (listeningIds.isEmpty() && "N5".equals(expectedLevel)) {
            // Keep existing in-progress N5 paths usable until the Minna bundle is imported.
            listeningIds = ownedSekaiComprehensionIds(lesson, expectedLevel, "LISTENING", gaps, key);
        }
        if (readingIds.isEmpty()) readingIds = ownedSpeedN3Ids(lesson, expectedLevel, "READING", gaps, key);
        if (listeningIds.isEmpty()) listeningIds = ownedSpeedN3Ids(lesson, expectedLevel, "LISTENING", gaps, key);
        // Owner decision 2026-10-06: a vocabulary-only lesson (>= 5 words, the flashcard threshold) or a dialogue-only
        // listening lesson is a valid target; only a lesson with nothing learnable beyond a short word list is not.
        if (kanjiIds.isEmpty() && grammarIds.isEmpty() && vocabIds.size() < 5 && dialogueIds.isEmpty()
                && readingIds.isEmpty() && listeningIds.isEmpty())
            gap(gaps,key,"KANJI_AND_GRAMMAR_NOT_READY");
        if(vocabIds.isEmpty() && kanjiIds.isEmpty() && grammarIds.isEmpty() && dialogueIds.isEmpty()
                && readingIds.isEmpty() && listeningIds.isEmpty()) gap(gaps,key,"EMPTY_LESSON");
        String revision=sha256(lesson.getUnit().getTextbook().getVersion()+"|"+lesson.getUpdatedAt()+"|"+vocabIds+"|"+kanjiIds+"|"+grammarIds+"|"+dialogueIds+"|"+readingIds+"|"+listeningIds+"|"+contexts);
        return new InteractiveContentSnapshot(lesson,revision,vocabIds,kanjiIds,grammarIds,dialogueIds,gaps,contexts,readingIds,listeningIds);
    }

    private List<Long> ownedSekaiComprehensionIds(Lesson lesson, String expectedLevel, String skill,
            List<CoverageGap> gaps, String gapKey) {
        var textbook = lesson.getUnit().getTextbook();
        if (!"N5".equals(expectedLevel) || !"N5".equals(textbook.getJlptLevel())
                || !"Sekai N5 — Giáo trình tiếng Nhật".equals(textbook.getTitle())
                || !"CURRICULUM".equals(lesson.getLessonType())) return List.of();
        int unitOrder = lesson.getUnit().getOrderIndex() == null ? 0 : lesson.getUnit().getOrderIndex();
        int lessonOrder = lesson.getOrderIndex() == null ? 0 : lesson.getOrderIndex();
        if (unitOrder < 1 || unitOrder > 5 || lessonOrder < 1 || lessonOrder > 5) {
            gap(gaps,gapKey,"SEKAI_N5_LESSON_ORDER_INVALID");
            return List.of();
        }
        int sourceLessonNumber = (unitOrder - 1) * 5 + lessonOrder;
        String lessonCode = "LP-OWNED-SEKAI-N5-L" + String.format(Locale.ROOT,"%02d",sourceLessonNumber);
        List<AssessmentQuestion> rows = assessmentQuestions
                .findByIsActiveTrueAndSkillAndJlptLevelAndSourceCodeStartingWithOrderByIdAsc(
                        skill,"N5",lessonCode+"-"+("READING".equals(skill)?"READ":"LISTEN")+"-");
        if (rows.isEmpty()) {
            gap(gaps,gapKey,"SEKAI_N5_"+skill+"_MISSING");
            return List.of();
        }
        List<Long> ids = rows.stream().filter(row -> row.getId()!=null && row.getIsActive()
                && skill.equals(row.getSkill()) && "N5".equals(row.getJlptLevel())
                && row.getSourceCode()!=null && row.getSourceCode().startsWith(lessonCode+"-"+("READING".equals(skill)?"READ":"LISTEN")+"-"))
                .map(AssessmentQuestion::getId).distinct().toList();
        if (ids.size()!=2) gap(gaps,gapKey,"SEKAI_N5_"+skill+"_COVERAGE_INVALID");
        return ids;
    }

    /** Title of the J-LAS N3 textbook the owner named for N3 paths and the BrSE N3 link. */
    static final String JLAS_N3_TEXTBOOK_TITLE = "Tiếng Nhật N3";

    /**
     * OD-44 follow-up (owner 2026-10-20): J-LAS N3 textbook chapter n carries the Speed Master N3 reading passages and
     * listening clips seeded as {@code LP-OWNED-SPEED-N3-Cnn-READ|LISTEN-Pp-Qqqq}, chapter = (unit - 1) * 5 + lesson.
     * Before that seed is applied a chapter simply has none — the readiness gate reports the missing skill.
     */
    private List<Long> ownedSpeedN3Ids(Lesson lesson, String expectedLevel, String skill,
            List<CoverageGap> gaps, String gapKey) {
        var textbook = lesson.getUnit().getTextbook();
        if (!"N3".equals(expectedLevel) || !"N3".equals(textbook.getJlptLevel())
                || !JLAS_N3_TEXTBOOK_TITLE.equals(textbook.getTitle())
                || !"CURRICULUM".equals(lesson.getLessonType())) return List.of();
        int unitOrder = lesson.getUnit().getOrderIndex() == null ? 0 : lesson.getUnit().getOrderIndex();
        int lessonOrder = lesson.getOrderIndex() == null ? 0 : lesson.getOrderIndex();
        int chapter = (unitOrder - 1) * 5 + lessonOrder;
        if (unitOrder < 1 || lessonOrder < 1 || lessonOrder > 5 || chapter > 27) return List.of();
        String prefix = "LP-OWNED-SPEED-N3-C" + String.format(Locale.ROOT, "%02d", chapter)
                + ("READING".equals(skill) ? "-READ-" : "-LISTEN-");
        List<AssessmentQuestion> rows = assessmentQuestions
                .findByIsActiveTrueAndSkillAndJlptLevelAndSourceCodeStartingWithOrderByIdAsc(skill, "N3", prefix);
        List<Long> ids = rows.stream().filter(row -> row.getId() != null && Boolean.TRUE.equals(row.getIsActive())
                && skill.equals(row.getSkill()) && "N3".equals(row.getJlptLevel())
                && row.getSourceCode() != null && row.getSourceCode().startsWith(prefix))
                .map(AssessmentQuestion::getId).distinct().toList();
        if (ids.size() != rows.size()) {
            gaps.add(new CoverageGap(gapKey, "SPEED_N3_" + skill + "_COVERAGE_INVALID"));
            return List.of();
        }
        return ids;
    }

    private List<Long> ownedMinnaListeningIds(Lesson lesson, String expectedLevel,
            List<CoverageGap> gaps, String gapKey) {
        var textbook = lesson.getUnit().getTextbook();
        if (!("N5".equals(expectedLevel) || "N4".equals(expectedLevel))
                || !expectedLevel.equals(textbook.getJlptLevel())
                || textbook.getTitle() == null || !textbook.getTitle().contains("Sekai " + expectedLevel)
                || !"CURRICULUM".equals(lesson.getLessonType())) return List.of();
        int unitOrder = lesson.getUnit().getOrderIndex() == null ? 0 : lesson.getUnit().getOrderIndex();
        int lessonOrder = lesson.getOrderIndex() == null ? 0 : lesson.getOrderIndex();
        if (unitOrder < 1 || unitOrder > 5 || lessonOrder < 1 || lessonOrder > 5) {
            gaps.add(new CoverageGap(gapKey, "MINNA_LESSON_ORDER_INVALID"));
            return List.of();
        }
        int number = ("N4".equals(expectedLevel) ? 25 : 0) + (unitOrder - 1) * 5 + lessonOrder;
        String prefix = "LP-OWNED-MINNA-" + expectedLevel + "-L"
                + String.format(Locale.ROOT, "%02d", number) + "-LISTEN-";
        List<AssessmentQuestion> rows = assessmentQuestions
                .findByIsActiveTrueAndSkillAndJlptLevelAndSourceCodeStartingWithOrderByIdAsc(
                        "LISTENING", expectedLevel, prefix);
        if (rows.isEmpty()) {
            if ("N4".equals(expectedLevel)) gaps.add(new CoverageGap(gapKey, "MINNA_LISTENING_MISSING"));
            return List.of();
        }
        List<Long> ids = rows.stream().filter(row -> row.getId() != null && Boolean.TRUE.equals(row.getIsActive())
                && "LISTENING".equals(row.getSkill()) && expectedLevel.equals(row.getJlptLevel())
                && row.getSourceCode() != null && row.getSourceCode().startsWith(prefix))
                .map(AssessmentQuestion::getId).distinct().toList();
        long clips = rows.stream().map(row -> {
            var code = row.getSourceCode();
            var match = java.util.regex.Pattern.compile("-LISTEN-(c\\d+)-").matcher(code == null ? "" : code);
            return match.find() ? match.group(1) : "";
        }).filter(code -> !code.isEmpty()).distinct().count();
        if (clips < 3 || ids.size() != rows.size()) gaps.add(new CoverageGap(gapKey, "MINNA_LISTENING_COVERAGE_INVALID"));
        return ids;
    }

    private List<Long> ownedMinnaReadingIds(Lesson lesson, String expectedLevel,
            List<CoverageGap> gaps, String gapKey) {
        var textbook = lesson.getUnit().getTextbook();
        if (!("N5".equals(expectedLevel) || "N4".equals(expectedLevel))
                || !expectedLevel.equals(textbook.getJlptLevel())
                || textbook.getTitle() == null || !textbook.getTitle().contains("Sekai " + expectedLevel)
                || !"CURRICULUM".equals(lesson.getLessonType())) return List.of();
        int unitOrder = lesson.getUnit().getOrderIndex() == null ? 0 : lesson.getUnit().getOrderIndex();
        int lessonOrder = lesson.getOrderIndex() == null ? 0 : lesson.getOrderIndex();
        if (unitOrder < 1 || unitOrder > 5 || lessonOrder < 1 || lessonOrder > 5) {
            gaps.add(new CoverageGap(gapKey, "MINNA_READING_LESSON_ORDER_INVALID"));
            return List.of();
        }
        int number = ("N4".equals(expectedLevel) ? 25 : 0) + (unitOrder - 1) * 5 + lessonOrder;
        String prefix = "LP-OWNED-MINNA-" + expectedLevel + "-L"
                + String.format(Locale.ROOT, "%02d", number) + "-READ-";
        List<AssessmentQuestion> rows = assessmentQuestions
                .findByIsActiveTrueAndSkillAndJlptLevelAndSourceCodeStartingWithOrderByIdAsc(
                        "READING", expectedLevel, prefix);
        if (rows.isEmpty()) {
            if ("N4".equals(expectedLevel)) gaps.add(new CoverageGap(gapKey, "MINNA_READING_MISSING"));
            return List.of();
        }
        List<Long> ids = rows.stream().filter(row -> row.getId() != null && Boolean.TRUE.equals(row.getIsActive())
                && "READING".equals(row.getSkill()) && expectedLevel.equals(row.getJlptLevel())
                && row.getSourceCode() != null && row.getSourceCode().startsWith(prefix))
                .map(AssessmentQuestion::getId).distinct().toList();
        if (ids.isEmpty() || ids.size() != rows.size()) {
            gaps.add(new CoverageGap(gapKey, "MINNA_READING_COVERAGE_INVALID"));
            return List.of();
        }
        return ids;
    }

    @Override public List<ReferenceVocabularyResponse> referenceVocabularyForLesson(Long lessonId) {
        var lesson=lessons.findWithUnitById(lessonId).orElse(null);
        // Called for an existing package, not for selecting a source for a new path.
        // A legacy book may lose source eligibility while its learner packages remain valid.
        if (lesson == null || !DraftStudySourcePolicy.isStudyableLesson(lesson.getStatus(),
                lesson.getUnit().getTextbook().getStatus(), lesson.getUnit().getTextbook().getIsLearningPathSource(),
                lesson.getUnit().getTextbook().getJlptLevel())) {
            return List.of();
        }
        List<VocabularyEntry> words=contentVocabulary.findVocabularyByLessonId(lessonId);
        if (words.size()<1 || words.size()>4
                || words.stream().anyMatch(word -> !"PUBLISHED".equals(word.getDictionaryStatus())
                        || blank(word.getExpression()) || blank(word.getReading()) || blank(word.getMeaning()))
                || words.stream().map(word -> word.getNormalizedExpression()+"\0"+word.getNormalizedReading())
                        .distinct().count()!=words.size()) {
            return List.of();
        }
        Map<Long, String> contextualMeanings = new HashMap<>();
        var contextualLinks = contentVocabulary.findLearningContextsByLessonId(lessonId);
        if (contextualLinks != null) for (var link : contextualLinks) {
            Long id = link.getVocabularyEntry().getId();
            String previous = contextualMeanings.putIfAbsent(id, link.getLearningMeaningVi());
            if (previous != null && !previous.equals(link.getLearningMeaningVi())) return List.of();
        }
        return words.stream().map(word -> new ReferenceVocabularyResponse(word.getId(), word.getExpression(),
                word.getReading(), contextualMeanings.getOrDefault(word.getId(), word.getMeaning()), word.getWordType())).toList();
    }
    private static void gap(List<CoverageGap> gaps,String key,String reason){gaps.add(new CoverageGap(key,reason));}
    private static boolean blank(String value){return value==null||value.isBlank();}
    private static boolean isJlptLevel(String value){return value!=null&&value.matches("N[1-5]");}
    private static String sha256(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}
