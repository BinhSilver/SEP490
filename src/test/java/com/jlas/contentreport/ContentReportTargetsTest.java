package com.jlas.contentreport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jlas.common.exception.ApiException;
import com.jlas.conjugation.*;
import com.jlas.dictionary.kanjimap.KanjiMapContentService;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static com.jlas.contentreport.ContentReportDtos.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ContentReportTargetsTest {
    JdbcTemplate jdbc=mock(JdbcTemplate.class);
    VerbSessionRepository sessions=mock(VerbSessionRepository.class);
    VerbCatalog verbs=mock(VerbCatalog.class);
    KanjiMapContentService kanjis=mock(KanjiMapContentService.class);
    ContentReportTargets targets=new ContentReportTargets(jdbc,sessions,verbs,kanjis);
    ObjectMapper mapper=new ObjectMapper();
    void jlpt(){
        when(jdbc.queryForList(contains("FROM jlpt_question"),eq(12L))).thenReturn(List.of(Map.of("id",12L,"exam_code","N3_MOCK_001","question_number",3,"question_text","問題","correct_option",2)));
        when(jdbc.queryForList(contains("FROM jlpt_option"),eq(12L))).thenReturn(List.of(Map.of("option_number",2,"option_text","答え")));
    }
    @Test void checksLearnerVisibilityAndGetsQuestionSnapshotOnServer(){
        jlpt();var row=targets.resolve(7L,new Request(Target.JLPT_QUESTION,Page.JLPT_EXAM,"12",null,null,null,Reason.CONTENT,null));
        assertEquals("12",row.code());assertEquals("問題",row.context().path("question_text").asText());
        verify(jdbc).queryForList(contains("e.status IN ('DRAFT','PUBLISHED')"),eq(12L));
        assertTrue(row.url().startsWith("/jlpt/exam?code="));
    }
    @Test void missingOrInvalidQuestionDoesNotCreateTarget(){
        when(jdbc.queryForList(contains("FROM jlpt_question"),eq(12L))).thenReturn(List.of());
        assertThrows(ApiException.class,()->targets.resolve(7L,new Request(Target.JLPT_QUESTION,Page.JLPT_EXAM,"12",null,null,null,Reason.CONTENT,null)));
        assertThrows(ApiException.class,()->targets.resolve(7L,new Request(Target.JLPT_QUESTION,Page.JLPT_EXAM,"12 OR 1=1",null,null,null,Reason.CONTENT,null)));
        assertThrows(ApiException.class,()->targets.resolve(7L,new Request(Target.JLPT_QUESTION,Page.KANJI_MAP,"12",null,null,null,Reason.CONTENT,null)));
    }
    @Test void retainsActualReportedAiTextAndSeparatesDifferentResponses(){
        jlpt();
        var first=targets.resolve(7L,new Request(Target.AI_EXPLANATION,Page.JLPT_PRACTICE,"12",null,null,"AI <script>sample</script>",Reason.AI,null));
        var second=targets.resolve(7L,new Request(Target.AI_EXPLANATION,Page.JLPT_PRACTICE,"12",null,null,"Another response",Reason.AI,null));
        assertNotEquals(first.code(),second.code());assertEquals("AI <script>sample</script>",first.context().path("reportedExplanation").asText());
        assertTrue(first.code().length()<=160);
        assertThrows(ApiException.class,()->targets.resolve(7L,new Request(Target.AI_EXPLANATION,Page.JLPT_PRACTICE,"12",null,null,"",Reason.AI,null)));
        assertThrows(ApiException.class,()->targets.resolve(7L,new Request(Target.AI_EXPLANATION,Page.JLPT_PRACTICE,"12",null,null,"x".repeat(20001),Reason.AI,null)));
    }
    @Test void onlyAcceptsAQuestionInTheReportersOwnVerbSession()throws Exception{
        var session=new VerbPracticeSession();session.setId("s1");
        var prompt=new VerbPracticeService.Prompt("q1","kaku",VerbEngine.Form.TE,null,List.of("書いて"));
        session.setPayload(mapper.writeValueAsString(new VerbPracticeService.Payload(null,List.of(prompt),List.of())));
        when(sessions.findByIdAndUserId("s1",7L)).thenReturn(Optional.of(session));
        when(sessions.findByIdAndUserId("s1",8L)).thenReturn(Optional.empty());
        when(verbs.verb("kaku")).thenReturn(new VerbCatalog.Verb("kaku","書く","かく","viết",1,"N5"));
        var request=new Request(Target.VERB_QUESTION,Page.VERB_PRACTICE,"q1","s1",null,null,Reason.ANSWER,null);
        var row=targets.resolve(7L,request);assertEquals("s1:q1",row.code());assertEquals("書いて",row.context().path("expected").asText());
        assertThrows(ApiException.class,()->targets.resolve(8L,request));
        assertThrows(ApiException.class,()->targets.resolve(7L,new Request(Target.VERB_QUESTION,Page.VERB_PRACTICE,"other","s1",null,null,Reason.ANSWER,null)));
    }
    @Test void resolvesOnlyExistingPublicMapNodesWithoutCopyingStrokePaths(){
        var graph=mapper.createObjectNode();graph.putArray("nodes").addObject().put("character","木").put("hanViet","Mộc").put("strokes","large paths");
        when(kanjis.graph(false)).thenReturn(graph);
        var row=targets.resolve(7L,new Request(Target.KANJI_MAP,Page.KANJI_MAP,null,null,"木",null,Reason.CONTENT,null));
        assertEquals("木",row.code());assertFalse(row.context().has("strokes"));
        assertThrows(ApiException.class,()->targets.resolve(7L,new Request(Target.KANJI_MAP,Page.KANJI_MAP,null,null,"不存在",null,Reason.CONTENT,null)));
    }
}
