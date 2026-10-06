package com.jlas.learning.service.impl;

import com.jlas.common.exception.*;
import com.jlas.dictionary.entity.VocabularyEntry;
import com.jlas.dictionary.repository.*;
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

    @Override public InteractiveContentSnapshot inspect(Long lessonId, String expectedLevel) {
        var lesson=lessons.findWithUnitById(lessonId).orElseThrow(()->new ApiException(ErrorCode.NOT_FOUND,"Không tìm thấy bài nguồn."));
        var gaps=new ArrayList<CoverageGap>(); String key="LESSON:"+lessonId;
        String actualLevel=lesson.getUnit().getTextbook().getJlptLevel();
        if(!"PUBLISHED".equals(lesson.getStatus())||!"PUBLISHED".equals(lesson.getUnit().getTextbook().getStatus())) gap(gaps,key,"SOURCE_NOT_PUBLISHED");
        if (!Boolean.TRUE.equals(lesson.getUnit().getTextbook().getIsLearningPathSource())) gap(gaps,key,"SOURCE_NOT_LEARNING_PATH");
        // JLPT stages must match their textbook exactly. Goal-specific communication stages
        // (BASIC/FUNCTIONAL/PROBLEM_SOLVING/FLEXIBLE) are validated by APPROVED lesson scope,
        // not by overloading textbook.jlpt_level with a non-JLPT value.
        if(isJlptLevel(expectedLevel)&&!Objects.equals(expectedLevel,actualLevel)) gap(gaps,key,"LEVEL_MISMATCH");
        List<VocabularyEntry> vocab=contentVocabulary.findVocabularyByLessonId(lessonId);
        if(vocab.stream().anyMatch(v->!"PUBLISHED".equals(v.getDictionaryStatus())||blank(v.getMeaning())||blank(v.getReading()))) gap(gaps,key,"VOCABULARY_NOT_READY");
        if(vocab.stream().map(v->v.getNormalizedExpression()+"\0"+v.getNormalizedReading()).distinct().count()!=vocab.size()) gap(gaps,key,"DUPLICATE_VOCABULARY_IDENTITY");
        List<Long> vocabIds=vocab.stream().map(VocabularyEntry::getId).distinct().sorted().toList();
        var links=vocabIds.isEmpty()?List.<com.jlas.dictionary.entity.VocabularyKanji>of():vocabularyKanji.findByVocabularyIdIn(vocabIds);
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
        if(!dialogueIds.isEmpty()) {
            var audioOwners=audio.findByOwnerTypeAndOwnerIdIn("CONTENT_ITEM",dialogueIds).stream().filter(a->!blank(a.getAudioUrl())).map(a->a.getOwnerId()).collect(Collectors.toSet());
            if(!audioOwners.containsAll(dialogueIds)) gap(gaps,key,"DIALOGUE_AUDIO_NOT_READY");
            if(dialogue.stream().anyMatch(d->blank(d.getContentItem().getContent()))) gap(gaps,key,"DIALOGUE_SCRIPT_NOT_READY");
        }
        // Owner decision 2026-10-06: a vocabulary-only lesson (>= 5 words, the flashcard threshold) or a dialogue-only
        // listening lesson is a valid target; only a lesson with nothing learnable beyond a short word list is not.
        if (kanjiIds.isEmpty() && grammarIds.isEmpty() && vocabIds.size() < 5 && dialogueIds.isEmpty())
            gap(gaps,key,"KANJI_AND_GRAMMAR_NOT_READY");
        if(vocabIds.isEmpty() && kanjiIds.isEmpty() && grammarIds.isEmpty() && dialogueIds.isEmpty()) gap(gaps,key,"EMPTY_LESSON");
        String revision=sha256(lesson.getUnit().getTextbook().getVersion()+"|"+lesson.getUpdatedAt()+"|"+vocabIds+"|"+kanjiIds+"|"+grammarIds+"|"+dialogueIds);
        return new InteractiveContentSnapshot(lesson,revision,vocabIds,kanjiIds,grammarIds,dialogueIds,gaps);
    }

    @Override public List<ReferenceVocabularyResponse> referenceVocabularyForLesson(Long lessonId) {
        var lesson=lessons.findWithUnitById(lessonId).orElse(null);
        if (lesson == null || !"PUBLISHED".equals(lesson.getStatus())
                || !"PUBLISHED".equals(lesson.getUnit().getTextbook().getStatus())
                || !Boolean.TRUE.equals(lesson.getUnit().getTextbook().getIsLearningPathSource())) {
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
        return words.stream().map(word -> new ReferenceVocabularyResponse(word.getId(), word.getExpression(),
                word.getReading(), word.getMeaning(), word.getWordType())).toList();
    }
    private static void gap(List<CoverageGap> gaps,String key,String reason){gaps.add(new CoverageGap(key,reason));}
    private static boolean blank(String value){return value==null||value.isBlank();}
    private static boolean isJlptLevel(String value){return value!=null&&value.matches("N[1-5]");}
    private static String sha256(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}
