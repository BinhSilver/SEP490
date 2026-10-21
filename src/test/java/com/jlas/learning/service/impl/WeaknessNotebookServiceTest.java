package com.jlas.learning.service.impl;

import com.jlas.assessment.repository.AssessmentAttemptRepository;
import com.jlas.assessment.repository.SmartTestSessionRepository;
import com.jlas.auth.entity.User;
import com.jlas.auth.repository.UserRepository;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.dictionary.repository.GrammarPointRepository;
import com.jlas.dictionary.repository.VocabularyEntryRepository;
import com.jlas.learning.entity.UserKnowledgeWeakness;
import com.jlas.learning.repository.UserKnowledgeWeaknessRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WeaknessNotebookServiceTest {
    @Mock UserKnowledgeWeaknessRepository weaknesses;
    @Mock AssessmentAttemptRepository attempts;
    @Mock SmartTestSessionRepository sessions;
    @Mock UserRepository users;
    @Mock GrammarPointRepository grammar;
    @Mock VocabularyEntryRepository vocabulary;
    @Mock SmartTestLearningProfileSource profiles;
    @Mock WeakTagPolicy policy;
    @Mock WeaknessNotebook notebook;
    @InjectMocks WeaknessNotebookService service;

    @Test void listFiltersPaginatesAndUsesOnlyAuthenticatedUsersRows() {
        var one = row(1L, "KANJI", "KANJI:食", "OPEN", 4);
        var two = row(2L, "VOCABULARY", "VOCAB:学校", "REVIEWED", 1);
        when(weaknesses.findByUserId(7L)).thenReturn(List.of(one,two));
        var result = service.list(7L, "KANJI", "N5", "OPEN", "食", "wrong", 0, 12);
        assertEquals(1, result.total());
        assertEquals("食", result.items().getFirst().title());
        assertTrue(result.items().getFirst().studyUrl().startsWith("/dictionary/index.html?q="));
        assertEquals(2, result.summary().total());
        assertEquals(1, result.summary().reviewed());
        verify(weaknesses).findByUserId(7L);
        verify(weaknesses, never()).findAll();
        var last = service.list(7L, "", "", "", "", "recent", Integer.MAX_VALUE, 50);
        assertTrue(last.items().isEmpty());
    }
    @Test void reviewRejectsAnotherUsersId() {
        when(users.findForCreditUpdate(7L)).thenReturn(Optional.of(User.builder().id(7L).build()));
        when(weaknesses.findByIdAndUserId(99L,7L)).thenReturn(Optional.empty());
        assertEquals(ErrorCode.NOT_FOUND, assertThrows(ApiException.class, () -> service.review(7L,99L,"REVIEWED")).getErrorCode());
        verify(weaknesses,never()).save(any());
    }
    @Test void reviewSavesTimestampWithoutInventingMasteryAndIsIdempotent() {
        var row = row(1L,"KANJI","KANJI:食","OPEN",1);
        when(users.findForCreditUpdate(7L)).thenReturn(Optional.of(User.builder().id(7L).build()));
        when(weaknesses.findByIdAndUserId(1L,7L)).thenReturn(Optional.of(row));
        service.review(7L,1L,"REVIEWED");
        var first = row.getLastReviewedAt();
        assertNotNull(first); assertNull(row.getMasteryScore());
        service.review(7L,1L,"REVIEWED");
        assertEquals(first,row.getLastReviewedAt());
        verify(weaknesses,times(1)).save(row);
        assertThrows(ApiException.class, () -> service.review(7L,1L,"MASTERED"));
    }
    @Test void rejectsMissingPrincipalAndUnknownFilters() {
        assertEquals(ErrorCode.UNAUTHORIZED,assertThrows(ApiException.class, () -> service.list(null,"","","","","recent",0,12)).getErrorCode());
        assertThrows(ApiException.class, () -> service.list(7L,"SQL","","","","recent",0,12));
        assertThrows(ApiException.class, () -> service.list(7L,"","","","","recent",0,100));
        verifyNoInteractions(weaknesses);
    }
    private UserKnowledgeWeakness row(Long id,String skill,String key,String status,int wrong) {
        return UserKnowledgeWeakness.builder().id(id).skill(skill).canonicalItem(key).status(status)
                .jlptLevel("N5").wrongCount(wrong).scorePercent(BigDecimal.valueOf(40)).detectedAt(LocalDateTime.now()).build();
    }
}
