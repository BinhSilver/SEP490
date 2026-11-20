package com.jlas.contentreport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jlas.auth.entity.User;
import com.jlas.auth.repository.UserRepository;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.moderation.service.UserActionPolicyPort;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import static com.jlas.contentreport.ContentReportDtos.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContentReportServiceTest {
    ContentReportRepository reports=mock(ContentReportRepository.class);
    ContentReportTargets targets=mock(ContentReportTargets.class);
    UserRepository users=mock(UserRepository.class);
    UserActionPolicyPort actions=mock(UserActionPolicyPort.class);
    ContentReportService service=new ContentReportService(reports,targets,users,actions);
    Request request=new Request(Target.JLPT_QUESTION,Page.JLPT_EXAM,"12",null,null,null,Reason.CONTENT," cần xem lại ");
    TargetSnapshot target=new TargetSnapshot(Target.JLPT_QUESTION,"12","N3 · Câu 1","/jlpt/exam?code=N3",new ObjectMapper().createObjectNode());
    @BeforeEach void setup(){when(users.findForCreditUpdate(7L)).thenReturn(Optional.of(mock(User.class)));when(targets.resolve(7L,request)).thenReturn(target);}
    @Test void recordsValidatedSnapshotAndOnlyReturnsReportId(){
        when(reports.insert(7L,request,target,"cần xem lại")).thenReturn(91L);
        assertEquals(91L,service.create(7L,request).reportId());
        verify(users).findForCreditUpdate(7L);verify(actions).assertCanAct(7L);
        verify(reports).insert(7L,request,target,"cần xem lại");
    }
    @Test void rejectsGuestsBeforeAnyDatabaseWrite(){assertEquals(ErrorCode.UNAUTHORIZED,assertThrows(ApiException.class,()->service.create(null,request)).getErrorCode());verifyNoInteractions(reports,targets,users,actions);}
    @Test void requiresDescriptionForOtherReason(){
        var other=new Request(Target.JLPT_QUESTION,Page.JLPT_EXAM,"12",null,null,null,Reason.OTHER,"   ");
        assertEquals(ErrorCode.VALIDATION_FAILED,assertThrows(ApiException.class,()->service.create(7L,other)).getErrorCode());
        verifyNoInteractions(reports,targets);
    }
    @Test void rejectsOverlongNotesWithoutTruncating(){
        var longNote=new Request(Target.JLPT_QUESTION,Page.JLPT_EXAM,"12",null,null,null,Reason.CONTENT,"a".repeat(1001));
        assertThrows(ApiException.class,()->service.create(7L,longNote));verifyNoInteractions(reports,targets);
    }
    @Test void rejectsOpenDuplicate(){
        when(reports.hasOpen(7L,Target.JLPT_QUESTION,"12")).thenReturn(true);
        assertEquals(ErrorCode.CONFLICT,assertThrows(ApiException.class,()->service.create(7L,request)).getErrorCode());
        verify(reports,never()).insert(any(),any(),any(),any());
    }
    @Test void enforcesTwentyReportsPerDay(){
        when(reports.countToday(7L)).thenReturn(20L);
        assertEquals(ErrorCode.TOO_MANY_REQUESTS,assertThrows(ApiException.class,()->service.create(7L,request)).getErrorCode());
        verify(reports,never()).insert(any(),any(),any(),any());
    }
    @Test void turnsDatabaseDuplicateRaceIntoConflict(){
        when(reports.insert(7L,request,target,"cần xem lại")).thenThrow(new DuplicateKeyException("uk_learning_report_open"));
        assertEquals(ErrorCode.CONFLICT,assertThrows(ApiException.class,()->service.create(7L,request)).getErrorCode());
    }
    @Test void cannotReopenOrSilentlyResolveAnAlreadyClosedReport(){
        assertThrows(ApiException.class,()->service.resolve(1L,7L,new Resolution(Status.OPEN,null)));
        when(reports.find(1L)).thenReturn(Optional.of(mock(Row.class)));
        when(reports.resolve(eq(1L),eq(7L),any())).thenReturn(0);
        assertEquals(ErrorCode.CONFLICT,assertThrows(ApiException.class,()->service.resolve(1L,7L,new Resolution(Status.RESOLVED,"xong"))).getErrorCode());
    }
}
