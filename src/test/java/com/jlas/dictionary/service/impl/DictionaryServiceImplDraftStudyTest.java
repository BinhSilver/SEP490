package com.jlas.dictionary.service.impl;

import com.jlas.auth.security.UserPrincipal;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.dictionary.entity.Lesson;
import com.jlas.dictionary.entity.Textbook;
import com.jlas.dictionary.entity.Unit;
import com.jlas.dictionary.mapper.DictionaryMapper;
import com.jlas.dictionary.repository.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OD-45 (owner 2026-10-20): signed-in learners open the J-LAS N3 textbook that is still DRAFT — its pages show the
 * DRAFT status (rendered "Chưa duyệt") and nothing is published. Other draft books stay hidden.
 */
@ExtendWith(MockitoExtension.class)
class DictionaryServiceImplDraftStudyTest {
    @Mock TextbookRepository textbookRepository;
    @Mock UnitRepository unitRepository;
    @Mock LessonRepository lessonRepository;
    @Mock ContentVocabularyRepository contentVocabularyRepository;
    @Mock GrammarPointRepository grammarPointRepository;
    @Mock DialogueRoleRepository dialogueRoleRepository;
    @Mock DialogueLineDetailRepository dialogueLineDetailRepository;
    @Mock ContentItemRepository contentItemRepository;
    @Mock ContentAudioRepository contentAudioRepository;
    @Mock LessonKanjiRepository lessonKanjiRepository;
    @Mock VocabularyKanjiRepository vocabularyKanjiRepository;
    @Mock KanjiReadingRepository kanjiReadingRepository;
    @Spy DictionaryMapper mapper = new DictionaryMapper();
    @InjectMocks DictionaryServiceImpl service;

    private final Textbook draftN3 = Textbook.builder().id(4L).title("Tiếng Nhật N3").jlptLevel("N3")
            .status("DRAFT").isLearningPathSource(true).version(1).build();

    @AfterEach void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    private void signInLearner() {
        var principal = new UserPrincipal(7L, "learner", "x", List.of());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    @Test void signedInLearnerOpensTheDraftN3TextbookWithItsDraftStatusAndNonArchivedLessons() {
        signInLearner();
        when(textbookRepository.findByIdAndStatus(4L, "PUBLISHED")).thenReturn(Optional.empty());
        when(textbookRepository.findById(4L)).thenReturn(Optional.of(draftN3));
        var unit = Unit.builder().id(20L).textbook(draftN3).title("Chủ đề 1").orderIndex(1).build();
        when(unitRepository.findByTextbookIdOrderByOrderIndexAscIdAsc(4L)).thenReturn(List.of(unit));
        when(lessonRepository.findByTextbookIdAndStatusNot(4L, "ARCHIVED")).thenReturn(List.of(
                Lesson.builder().id(61L).unit(unit).title("Bài 1").status("DRAFT").orderIndex(1).build()));

        var page = service.getTextbookUnits(4L);

        assertEquals("DRAFT", page.getTextbook().getStatus(), "the page labels it, it is not published");
        assertEquals(1, page.getUnits().getFirst().getLessons().size());
        assertEquals("DRAFT", draftN3.getStatus());
    }

    @Test void anonymousVisitorAndOtherDraftBooksStayNotFound() {
        when(textbookRepository.findByIdAndStatus(any(), eq("PUBLISHED"))).thenReturn(Optional.empty());
        lenient().when(textbookRepository.findById(4L)).thenReturn(Optional.of(draftN3));
        assertEquals(ErrorCode.NOT_FOUND,
                assertThrows(ApiException.class, () -> service.getTextbookUnits(4L)).getErrorCode(), "anonymous");

        signInLearner();
        var draftN4 = Textbook.builder().id(2L).title("Sekai N4").jlptLevel("N4").status("DRAFT")
                .isLearningPathSource(true).version(1).build();
        when(textbookRepository.findById(2L)).thenReturn(Optional.of(draftN4));
        assertEquals(ErrorCode.NOT_FOUND,
                assertThrows(ApiException.class, () -> service.getTextbookUnits(2L)).getErrorCode(), "draft N4");
        verify(lessonRepository, never()).findByTextbookIdAndStatusNot(any(), any());
    }

    @Test void signedInLearnerReadsADraftLessonOfTheOpenN3BookButNotAnArchivedOne() {
        signInLearner();
        var unit = Unit.builder().id(20L).textbook(draftN3).title("Chủ đề 1").orderIndex(1).build();
        when(lessonRepository.findWithUnitById(61L)).thenReturn(Optional.of(
                Lesson.builder().id(61L).unit(unit).title("Bài 1").status("DRAFT").lessonType("CURRICULUM").build()));
        lenient().when(contentVocabularyRepository.findVocabularyByLessonId(61L)).thenReturn(List.of());
        lenient().when(vocabularyKanjiRepository.findByVocabularyIdIn(anyList())).thenReturn(List.of());

        assertEquals(61L, service.getLessonContent(61L).getId());

        when(lessonRepository.findWithUnitById(62L)).thenReturn(Optional.of(
                Lesson.builder().id(62L).unit(unit).title("Bài cũ").status("ARCHIVED").lessonType("CURRICULUM").build()));
        assertEquals(ErrorCode.NOT_FOUND,
                assertThrows(ApiException.class, () -> service.getLessonContent(62L)).getErrorCode());
    }

    @Test void libraryListsTheOpenDraftN3BookForSignedInLearnersOnly() {
        var published = Textbook.builder().id(1L).title("Sekai N5").jlptLevel("N5").status("PUBLISHED").version(1).build();
        when(textbookRepository.findLibraryTextbooks(false, "PUBLISHED")).thenReturn(List.of(published));
        lenient().when(textbookRepository.findByJlptLevelInAndStatusAndIsLearningPathSourceTrueOrderByIdAsc(anyList(), eq("DRAFT")))
                .thenReturn(List.of(draftN3));

        assertEquals(List.of(1L), service.listTextbooks().stream().map(t -> t.getId()).toList(), "anonymous");

        signInLearner();
        assertEquals(List.of(1L, 4L), service.listTextbooks().stream().map(t -> t.getId()).toList());
    }
}
