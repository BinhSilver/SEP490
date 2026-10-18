package com.jlas.conjugation;

import com.jlas.auth.entity.*;
import com.jlas.common.exception.*;
import jakarta.persistence.EntityManager;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static com.jlas.conjugation.VerbEngine.Form;
import static com.jlas.conjugation.VerbPracticeService.*;

@DataJpaTest(properties={"spring.profiles.include=","spring.datasource.url=jdbc:h2:mem:verb-practice;MODE=MySQL;DB_CLOSE_DELAY=-1","spring.jpa.hibernate.ddl-auto=create-drop"},showSql=false)
@ContextConfiguration(classes=VerbPracticePersistenceTest.Cfg.class)
@Sql(scripts="file:sql/seeds/verb_practice_catalog.sql")
class VerbPracticePersistenceTest {
    @Configuration(proxyBeanMethods=false) @EntityScan({"com.jlas.conjugation","com.jlas.auth.entity"})
    @EnableJpaRepositories(basePackages={"com.jlas.conjugation","com.jlas.auth.repository"})
    @Import({VerbCatalog.class,VerbPracticeService.class}) static class Cfg {}
    @Autowired EntityManager em;
    @Autowired VerbPracticeService service;
    @Autowired VerbProgressRepository progress;
    @Autowired VerbSessionRepository sessions;
    @Autowired VerbCatalog catalog;
    Long user,other;
    Instant now=Instant.parse("2026-10-04T12:00:00Z");
    @BeforeEach void setup(){var role=Role.builder().name("LEARNER").build();em.persist(role);user=user(role,"one");other=user(role,"two");ReflectionTestUtils.setField(service,"clock",Clock.fixed(now,ZoneOffset.UTC));}
    Long user(Role role,String key){var u=User.builder().role(role).email(key+"@verb.test").loginType("LOCAL").build();em.persist(u);em.flush();return u.getId();}
    Settings config(Mode mode){return new Settings(mode,List.of(Form.TE,Form.NAI),0,"ALL",5);}
    String answer(Question q){var v=catalog.verbs().stream().filter(x->x.word().equals(q.word())&&x.reading().equals(q.reading())).findFirst().orElseThrow();return VerbEngine.conjugate(v,q.form(),true);}
    @Test void gradesOncePersistsAfterReloadAndKeepsOwnership(){
        var s=service.start(user,config(Mode.INPUT));assertEquals(0,s.answered());assertNull(s.feedback());assertTrue(s.results().isEmpty());
        var q=s.question();var graded=service.answer(user,s.id(),q.id(),answer(q),false);
        assertTrue(graded.feedback().correct());assertEquals(1,graded.correct());
        em.flush();em.clear();
        var retry=service.answer(user,s.id(),q.id(),"wrong retry",false);assertTrue(retry.feedback().correct());
        assertEquals(1,service.get(user,s.id()).answered());assertEquals(1,service.dashboard(user).attempted());
        assertEquals(ErrorCode.NOT_FOUND,assertThrows(ApiException.class,()->service.get(other,s.id())).getErrorCode());
    }
    @Test void wrongAnswerBecomesReviewAndCorrectReviewClearsIt(){
        var s=service.start(user,config(Mode.INPUT));service.answer(user,s.id(),s.question().id(),"まちがい",false);
        assertEquals(1,service.dashboard(user).mistakes());
        var review=service.start(user,config(Mode.REVIEW));assertEquals(1,review.total());
        var done=service.answer(user,review.id(),review.question().id(),answer(review.question()),false);
        assertTrue(done.finished());assertEquals(0,service.dashboard(user).mistakes());assertEquals(2,service.dashboard(user).attempted());
    }
    @Test void dailyProgressCountsAnswerDayAcrossMidnight(){
        var before=Instant.parse("2026-10-04T16:59:59Z");
        ReflectionTestUtils.setField(service,"clock",Clock.fixed(before,ZoneOffset.UTC));
        var s=service.start(user,config(Mode.INPUT));
        var next=service.answer(user,s.id(),s.question().id(),answer(s.question()),false);
        assertEquals(1,service.dashboard(user).today());
        ReflectionTestUtils.setField(service,"clock",Clock.fixed(before.plusSeconds(2),ZoneOffset.UTC));
        assertEquals(0,service.dashboard(user).today());
        service.answer(user,s.id(),next.question().id(),answer(next.question()),false);
        assertEquals(1,service.dashboard(user).today());
        assertEquals(2,service.dashboard(user).attempted());
        assertEquals(config(Mode.INPUT),service.get(user,s.id()).settings());
    }
    @Test void speedDeadlineCannotBeExtendedByClientOrPageReload(){
        var s=service.start(user,config(Mode.SPEED));assertEquals(now.plusSeconds(60),s.deadline());
        ReflectionTestUtils.setField(service,"clock",Clock.fixed(now.plusSeconds(61),ZoneOffset.UTC));
        var result=service.answer(user,s.id(),s.question().id(),answer(s.question()),false);
        assertTrue(result.finished());assertEquals(0,result.answered());assertTrue(service.get(user,s.id()).finished());
    }
    @Test void allModesHaveValidQuestionsAndChoicesHaveOneAcceptedAnswer(){
        for(var mode:List.of(Mode.CHOICE,Mode.INPUT,Mode.CHAIN,Mode.CONTEXT,Mode.SPEED)) {
            var s=service.start(user,config(mode));assertNotNull(s.question());
            if(mode==Mode.CHOICE){assertEquals(4,new HashSet<>(s.question().choices()).size());var q=s.question();var v=catalog.verbs().stream().filter(x->x.word().equals(q.word())&&x.reading().equals(q.reading())).findFirst().orElseThrow();assertEquals(1,q.choices().stream().filter(x->VerbEngine.correct(v,q.form(),x)).count());}
            if(mode==Mode.CONTEXT)assertTrue(s.question().sentence().contains("___"));
            if(mode==Mode.CHAIN){var first=s.question();var next=service.answer(user,s.id(),first.id(),answer(first),false).question();assertEquals(first.word(),next.word());assertNotEquals(first.form(),next.form());}
        }
        assertEquals(1,sessions.findByUserIdAndFinishedAtIsNull(user).size());
    }
    @Test void allFiveLevelsFilterEveryQuestionAndOfferContexts(){
        for(var level:List.of("N5","N4","N3","N2","N1")) {
            for(int group=1;group<=3;group++) {
                var s=service.start(user,new Settings(Mode.INPUT,List.of(Form.TE,Form.NAI),group,level,5));
                for(int i=0;i<s.total();i++){
                    var q=s.question();assertEquals(level,q.level());assertEquals(group,q.group());
                    s=service.answer(user,s.id(),q.id(),answer(q),false);
                }
                assertTrue(s.finished());assertEquals(s.total(),s.correct());
            }
            var c=service.start(user,new Settings(Mode.CONTEXT,List.of(Form.values()),0,level,30));
            assertNotNull(c.question().sentence());assertEquals(level,c.question().level());
            for(int i=0;i<c.total();i++){
                var q=c.question();assertEquals(level,q.level());assertTrue(q.sentence().contains("___"));
                c=service.answer(user,c.id(),q.id(),answer(q),false);
            }
            assertTrue(c.finished());
        }
        assertEquals(5,service.dashboard(user).levels().size());
        assertEquals(catalog.verbs().size(),service.dashboard(user).levels().stream().mapToLong(LevelInfo::verbCount).sum());
        assertThrows(ApiException.class,()->service.start(user,new Settings(Mode.INPUT,List.of(Form.TE),0,"N6",5)));
    }
    @Test void rejectsEmptyFiltersAndStaleQuestionWithoutUpdatingProgress(){
        assertThrows(ApiException.class,()->service.start(user,new Settings(Mode.INPUT,List.of(),0,"ALL",10)));
        assertThrows(ApiException.class,()->service.start(user,config(Mode.REVIEW)));
        var s=service.start(user,config(Mode.INPUT));
        assertEquals(ErrorCode.CONFLICT,assertThrows(ApiException.class,()->service.answer(user,s.id(),"stale","かいて",false)).getErrorCode());
        assertTrue(progress.findByUserId(user).isEmpty());
    }
}
