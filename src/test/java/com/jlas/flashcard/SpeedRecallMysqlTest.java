package com.jlas.flashcard;

import com.jlas.auth.entity.*;
import com.jlas.flashcard.dto.SpeedRecallDtos.*;
import com.jlas.flashcard.entity.*;
import com.jlas.flashcard.repository.*;
import com.jlas.flashcard.service.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Opt-in against an already migrated local MySQL. Every fixture and mutation rolls back. */
@SpringBootTest
@Transactional
@EnabledIfSystemProperty(named="speedRecall.mysql", matches="true")
class SpeedRecallMysqlTest {
    @Autowired SpeedRecallService service;
    @Autowired EntityManager em;
    @Autowired FlashcardSpeedRecallAnswerRepository answers;
    @Autowired FlashcardSpeedRecallSessionRepository sessions;
    @Autowired FlashcardStudySessionRepository studies;
    @Autowired FlashcardCardProgressRepository progress;
    @Autowired org.springframework.web.context.WebApplicationContext webContext;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    @MockitoBean SpeedRecallClock clock;
    User user;
    FlashcardSet set;
    LocalDateTime now;
    int position;
    String key() { return UUID.randomUUID().toString(); }
    @BeforeEach void fixtures() {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) return;
        em.unwrap(org.hibernate.Session.class).doWork(connection -> {
            String url = connection.getMetaData().getURL();
            assertTrue(url.matches("jdbc:mysql://(localhost|127\\.0\\.0\\.1)(:|/).*"), "Tests require local MySQL");
        });
        position = 0;
        now = LocalDateTime.of(2020,1,1,0,0); when(clock.now()).thenAnswer(i -> now);
        Role role = em.createQuery("select r from Role r", Role.class).setMaxResults(1).getSingleResult();
        user = User.builder().email("speed-recall-test-" + key() + "@example.invalid").loginType("LOCAL").role(role).build();
        em.persist(user);
        set = FlashcardSet.builder().owner(user).title("Speed Recall rollback fixture").build(); em.persist(set);
        card("学校","がっこう"); card("約束","やくそく");
        card("学校","がっこう"); card("invalid",null);
        em.flush();
    }
    FlashcardCard card(String front,String reading) {
        var c = FlashcardCard.builder().set(set).front(front).reading(reading).meaning("Fixture").position(position++).build(); em.persist(c); return c;
    }
    View create(int count) { return service.create(set.getId(),user.getId(),new CreateRequest(key(),"ALL",count,false)); }
    View next(View v,int index) { return service.next(set.getId(),user.getId(),v.id(),new NextRequest(index)); }
    View answer(View v,String value) { return service.answer(set.getId(),user.getId(),v.id(),new AnswerRequest(v.question().id(),key(),value),false); }
    View state(View v) { return service.state(set.getId(),user.getId(),v.id()); }
    void seconds(int n) { now = now.plusSeconds(n); }
    @Test void capsDeduplicatesAndKeepsCreationIdempotent() {
        var o = service.options(set.getId(),user.getId());
        assertEquals(4,o.totalCards()); assertEquals(2,o.eligible()); assertEquals(0,o.starred());
        var req = new CreateRequest(key(),"ALL",20,false);
        var first = service.create(set.getId(),user.getId(),req);
        var same = service.create(set.getId(),user.getId(),req);
        assertEquals(first.id(),same.id()); assertEquals(2,first.stats().total());
        assertNull(first.question()); assertTrue(first.results().isEmpty());
        assertEquals(1,service.history(set.getId(),user.getId(),0,20).totalElements());
    }
    @Test void acceptsMoreThanTwentyEligibleCards() {
        for (int i = 0; i < 25; i++) card("語" + i, "ご" + "あ".repeat(i + 1));
        em.flush();
        var session = service.create(set.getId(),user.getId(),new CreateRequest(key(),"ALL",25,false));
        assertEquals(25,session.stats().total());
    }
    @Test void answerReplayDoesNotAwardTwiceAndPersonalBestUsesCompletedSession() {
        var v = next(create(1),0); seconds(3);
        var req = new AnswerRequest(v.question().id(),key(),v.question().expectedAnswer());
        var done = service.answer(set.getId(),user.getId(),v.id(),req,false);
        var same = service.answer(set.getId(),user.getId(),v.id(),req,false);
        assertEquals("FINISHED",done.state()); assertEquals(130,done.stats().score());
        assertEquals(done.stats(),same.stats()); assertEquals(130,done.personalBestScore());
        em.flush(); em.clear();
        assertEquals("COMPLETED",studies.findById(v.id()).orElseThrow().getStatus());
        assertEquals(1,studies.countBySetIdAndStatus(set.getId(),"COMPLETED"));
        assertTrue(progress.findByUserIdAndCardSetId(user.getId(),set.getId()).isEmpty());
        assertEquals(409, assertThrows(ResponseStatusException.class, () ->
            service.answer(set.getId(),user.getId(),v.id(),new AnswerRequest(req.questionId(),key(),"wrong"),false)).getStatusCode().value());
    }
    @Test void deadlineCannotBeResetByReloadOrNextReplayAndLateCorrectIsTimeout() {
        var v = next(create(1),0); seconds(3);
        assertEquals(v.question().deadlineAt(),next(v,0).question().deadlineAt());
        assertEquals(v.question().deadlineAt(),state(v).question().deadlineAt());
        seconds(3);
        var done = answer(v,v.question().expectedAnswer());
        assertEquals("FINISHED",done.state()); assertEquals(1,done.stats().timeout());
        assertEquals(0,done.stats().score()); assertEquals(6000,done.stats().averageResponseMs());
    }
    @Test void pausePreservesRemainingTimeAndResponseTimeExcludesPause() {
        var v = next(create(1),0); seconds(2);
        var paused = service.pause(set.getId(),user.getId(),v.id());
        assertEquals(4000,paused.pausedRemainingMs());
        seconds(60);
        assertEquals("PAUSED",state(v).state());
        var resumed = service.resume(set.getId(),user.getId(),v.id());
        assertEquals(4000,Duration.between(resumed.serverNow(),resumed.question().deadlineAt()).toMillis());
        seconds(1);
        assertEquals(3000,answer(resumed,resumed.question().expectedAnswer()).stats().averageResponseMs());
    }
    @Test void retryUsesOnlyWrongExistingCardsAndSnapshotsSurviveCardDeletion() {
        var first = next(create(2),0); seconds(1);
        var feedback = answer(first,"ちがう"); assertEquals("ANSWER_FEEDBACK",feedback.state());
        assertEquals("ANSWER_FEEDBACK",next(feedback,1).state()); seconds(2);
        var second = next(feedback,1); seconds(1);
        var done = answer(second,second.question().expectedAnswer());
        var retry = service.retry(set.getId(),user.getId(),done.id(),new RetryRequest(key()));
        assertEquals(1,retry.stats().total()); assertEquals("RETRY",retry.filter());
        var wrong = done.results().stream().filter(a -> a.result().equals("WRONG")).findFirst().orElseThrow();
        em.createNativeQuery("DELETE FROM flashcard_card WHERE id = :id").setParameter("id",wrong.cardId()).executeUpdate();
        em.flush(); em.clear();
        var restored = state(done);
        var snapshot = restored.results().stream().filter(a -> a.result().equals("WRONG")).findFirst().orElseThrow();
        assertNull(snapshot.cardId()); assertEquals(wrong.question(),snapshot.question()); assertEquals(wrong.reading(),snapshot.reading());
    }
    @Test void ownershipAndVisibilityAreCheckedForEverySessionRequest() {
        var v = create(1);
        assertEquals(401,assertThrows(ResponseStatusException.class,() -> service.state(set.getId(),null,v.id())).getStatusCode().value());
        assertEquals(403,assertThrows(ResponseStatusException.class,() -> service.state(set.getId(),user.getId()+999999,v.id())).getStatusCode().value());
        set.setDraft(true); em.flush();
        assertEquals(400,assertThrows(ResponseStatusException.class,() -> state(v)).getStatusCode().value());
    }
    @Test void abandonedAndExpiredSessionsNeverCountAsCompleted() {
        var v = next(create(1),0);
        assertEquals("ABANDONED",service.abandon(set.getId(),user.getId(),v.id()).state());
        assertEquals(0,state(v).stats().score());
        var expiring = create(1); now = now.plusMinutes(31);
        assertEquals("EXPIRED",state(expiring).state());
        assertEquals("ABANDONED",studies.findById(expiring.id()).orElseThrow().getStatus());
        assertEquals(0,studies.countBySetIdAndStatus(set.getId(),"COMPLETED"));
    }
    @Test void earlyTimeoutAndWrongSessionQuestionDoNotResolveQuestion() {
        var v = next(create(1),0);
        var early = service.answer(set.getId(),user.getId(),v.id(),new AnswerRequest(v.question().id(),key(),""),true);
        assertEquals("PLAYING",early.state()); assertEquals(0,early.stats().wrong());
        var other = next(create(1),0);
        assertEquals(409,assertThrows(ResponseStatusException.class,() ->
            service.answer(set.getId(),user.getId(),v.id(),new AnswerRequest(other.question().id(),key(),"あ"),false)).getStatusCode().value());
    }

    @Test void httpRoutesEnforceAuthenticationValidationAndReturnApiEnvelope() throws Exception {
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(webContext)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity()).build();
        String path = "/api/flashcards/" + set.getId() + "/speed-recall";
        var principal = new com.jlas.auth.security.UserPrincipal(user.getId(),user.getEmail(),"",java.util.List.of());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path + "/options"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path + "/options")
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user(principal)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.success").value(true))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.eligible").value(2));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/quiz/from-flashcard/" + set.getId())
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user(principal))
                .contentType("application/json").content("{}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.cardCount").value(4));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path + "/sessions")
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user(principal))
                .contentType("application/json")
                .content("{\"requestId\":\"" + key() + "\",\"filter\":\"ALL\",\"count\":0,\"autoSubmit\":false}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path + "/sessions")
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user(principal))
                .contentType("application/json")
                .content("{\"requestId\":\"" + key() + "\",\"filter\":\"ALL\",\"count\":1,\"autoSubmit\":false}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.state").value("READY"));
    }

    @Test
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void simultaneousCreateAndAnswerRequestsAreSerializedByMysqlLocks() throws Exception {
        var tx = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> fixtures());
        Long setId = set.getId(), userId = user.getId();
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var request = new CreateRequest(key(),"ALL",1,false);
            var barrier = new java.util.concurrent.CyclicBarrier(2);
            java.util.concurrent.Callable<View> create = () -> { barrier.await(); return service.create(setId,userId,request); };
            var a = executor.submit(create); var b = executor.submit(create);
            var first = a.get(15,java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(first.id(),b.get(15,java.util.concurrent.TimeUnit.SECONDS).id());
            var playing = service.next(setId,userId,first.id(),new NextRequest(0));
            var answer = new AnswerRequest(playing.question().id(),key(),playing.question().expectedAnswer());
            java.util.concurrent.Callable<View> submit = () -> { barrier.await(); return service.answer(setId,userId,first.id(),answer,false); };
            var c = executor.submit(submit); var d = executor.submit(submit);
            var result = c.get(15,java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(result.stats(),d.get(15,java.util.concurrent.TimeUnit.SECONDS).stats());
            assertEquals(1,result.stats().correct()); assertEquals(155,result.stats().score());
            assertEquals(1,service.history(setId,userId,0,20).totalElements());
        } finally {
            // Remove only this committed fixture; all other tests use transaction rollback.
            tx.executeWithoutResult(status -> {
                em.createNativeQuery("DELETE FROM flashcard_study_session WHERE user_id = :id").setParameter("id",userId).executeUpdate();
                em.createNativeQuery("DELETE FROM flashcard_card WHERE set_id = :id").setParameter("id",setId).executeUpdate();
                em.createNativeQuery("DELETE FROM flashcard_set WHERE id = :id").setParameter("id",setId).executeUpdate();
                em.createNativeQuery("DELETE FROM users WHERE id = :id").setParameter("id",userId).executeUpdate();
            });
        }
    }
}
