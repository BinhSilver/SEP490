package com.jlas.learning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jlas.auth.entity.Role;
import com.jlas.auth.entity.User;
import com.jlas.auth.repository.UserRepository;
import com.jlas.dictionary.entity.Lesson;
import com.jlas.learning.entity.LearningPathPackage;
import com.jlas.learning.entity.UserLearningPath;
import com.jlas.learning.entity.UserLearningPathItem;
import com.jlas.learning.enums.LearningActivityState;
import com.jlas.learning.enums.LearningActivityType;
import com.jlas.learning.enums.LearningExperienceVersion;
import com.jlas.learning.enums.LearningPackageStatus;
import com.jlas.learning.repository.LearningPathPackageRepository;
import com.jlas.learning.repository.LearningQuizSessionRepository;
import com.jlas.learning.repository.UserLearningPathItemRepository;
import com.jlas.learning.repository.UserLearningPathRepository;
import com.jlas.learning.service.impl.InteractiveLearningPathServiceImpl;
import com.jlas.moderation.credit.service.UserCreditService;
import com.jlas.moderation.repository.UserCreditTransactionRepository;
import jakarta.persistence.EntityManager;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * MySQL-backed verification of the chapter-completion credit reward (owner directive 2026-10-14, item
 * "Kiểm chứng thưởng bằng giao dịch MySQL"). The unit test
 * {@link InteractiveLearningPathServiceTest#chapterSplitAcrossSegmentsRewardsOnlyWhenTheWholeChapterCompletes}
 * proves the <em>timing</em> against a mock {@link UserCreditService}; this test drives the real
 * {@link UserCreditService} (real {@link UserCreditTransactionRepository} + {@link UserRepository} against a
 * scratch MySQL DB) so the assertions read the actual {@code user_credit_transaction} ledger and
 * {@code users.credit_balance}:
 *
 * <ul>
 *   <li>a chapter split into two segments: the first segment's completion writes <em>no</em> chapter reward;
 *       the final segment writes exactly one 5 CP {@code STUDY_REWARD} referenced by {@code SOURCE_LESSON:&lt;id&gt;};</li>
 *   <li>a retry of the already-completed final segment writes nothing new;</li>
 *   <li>a lesson-less package is its own chapter and is referenced by {@code PACKAGE:&lt;id&gt;};</li>
 *   <li>two concurrent completions of the final segment credit the chapter exactly once (the
 *       {@code uk_credit_transaction_reference} backstop, already exercised under concurrency by
 *       {@code CreditAwardApplyMySqlServiceTest}).</li>
 * </ul>
 *
 * <p>OFF by default (H2 cannot reproduce InnoDB row locking or the unique backstop under load):</p>
 *
 * <pre>
 *   mvn test -Dtest=ChapterRewardMySqlServiceTest \
 *     -Djlas.mysql.url=jdbc:mysql://localhost:3306/ -Djlas.mysql.user=root (password via JLAS_MYSQL_TEST_PASSWORD)
 * </pre>
 */
@EnabledIfSystemProperty(named = "jlas.mysql.url", matches = "jdbc:mysql:.+")
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.MySQLDialect"
})
@ContextConfiguration(classes = ChapterRewardMySqlServiceTest.Cfg.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ChapterRewardMySqlServiceTest {

    @Configuration(proxyBeanMethods = false)
    @EntityScan("com.jlas")
    @EnableJpaRepositories(basePackages = {
            "com.jlas.auth.repository",
            "com.jlas.moderation.repository"
    })
    static class Cfg {
    }

    private static final String BASE_URL = System.getProperty("jlas.mysql.url").replaceAll("/+$", "");
    private static final String MYSQL_USER = System.getProperty("jlas.mysql.user", "root");
    private static final String MYSQL_PASSWORD =
            System.getenv().getOrDefault("JLAS_MYSQL_TEST_PASSWORD", "");
    private static final String DATABASE =
            "jlas_chapter_reward_" + java.util.UUID.randomUUID().toString().replace("-", "");
    private static final java.util.concurrent.atomic.AtomicInteger SEQ =
            new java.util.concurrent.atomic.AtomicInteger();

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) throws Exception {
        execute(BASE_URL + "/",
                "CREATE DATABASE " + DATABASE + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        String url = BASE_URL + "/" + DATABASE
                + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Ho_Chi_Minh&characterEncoding=UTF-8";
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> MYSQL_USER);
        registry.add("spring.datasource.password", () -> MYSQL_PASSWORD);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
    }

    @AfterAll
    static void dropDatabase() throws Exception {
        execute(BASE_URL + "/", "DROP DATABASE " + DATABASE);
    }

    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository userRepository;
    @Autowired UserCreditTransactionRepository txRepository;

    UserLearningPathRepository paths;
    LearningPathPackageRepository packages;
    UserLearningPathItemRepository items;
    LearningQuizSessionRepository quizSessions;
    UserCreditService credits;
    InteractiveLearningPathServiceImpl service;
    TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        paths = mock(UserLearningPathRepository.class);
        packages = mock(LearningPathPackageRepository.class);
        items = mock(UserLearningPathItemRepository.class);
        quizSessions = mock(LearningQuizSessionRepository.class);
        credits = new UserCreditService(txRepository, userRepository, mock(ApplicationEventPublisher.class));
        service = new InteractiveLearningPathServiceImpl(paths, packages, items, quizSessions);
        setField("creditService", credits);
        tx = new TransactionTemplate(transactions);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void chapterSplitAcrossSegmentsRewardsExactlyOnceInTheLedger() {
        long userId = seed();
        var path = interactivePath(userId);
        var segment1 = segment(path, 11L, 1);
        var segment2 = segment(path, 12L, 2);
        segment2.setSourceLesson(segment1.getSourceLesson()); // same source lesson -> one chapter
        var first = activities(segment1, 1L);
        var second = activities(segment2, 101L);
        markAllTerminal(first);
        markAllTerminal(second);
        stubChapter(userId, segment1, segment2, first, second);

        // Segment 1 completes the chapter's first half: NO chapter reward yet.
        tx.executeWithoutResult(status -> service.packageDetail(userId, 11L));
        assertEquals(0, txCount(userId), "first segment must not write a chapter reward transaction");
        assertEquals(0, balance(userId), "first segment must not credit the learner");

        // Segment 2 completes the chapter: exactly one 5 CP transaction.
        tx.executeWithoutResult(status -> service.packageDetail(userId, 12L));
        assertEquals(1, txCount(userId), "completing the chapter must write exactly one reward");
        assertEquals(5, amount(userId), "the chapter reward must be exactly 5 CP");
        assertEquals("STUDY_REWARD", jdbc.queryForObject(
                "SELECT reason FROM user_credit_transaction WHERE user_id = ?", String.class, userId));
        assertEquals("SOURCE_LESSON", jdbc.queryForObject(
                "SELECT reference_type FROM user_credit_transaction WHERE user_id = ?", String.class, userId),
                "the chapter is referenced by its stable source lesson, never per-package");
        assertEquals(111L, jdbc.queryForObject(
                "SELECT reference_id FROM user_credit_transaction WHERE user_id = ?", Long.class, userId));
        assertEquals(5, balance(userId), "the learner must hold exactly 5 CP after the chapter completes");

        // Retry of the already-completed final segment: no re-grant.
        tx.executeWithoutResult(status -> service.packageDetail(userId, 12L));
        assertEquals(1, txCount(userId), "a retry must not write a second reward");
        assertEquals(5, balance(userId), "a retry must leave the balance unchanged");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void lessonlessPackageRewardsItsOwnChapterByPackageReference() {
        long userId = seed();
        var path = interactivePath(userId);
        var pkg = warmUpPackage(path, 50L, 1);
        var activities = List.of(UserLearningPathItem.builder().id(501L).learningPath(path)
                .learningPackage(pkg).orderIndex(1).activityType(LearningActivityType.VOCABULARY)
                .activityState(LearningActivityState.PASSED).build());
        when(packages.findOwnedByIdForUpdate(50L, userId)).thenReturn(Optional.of(pkg));
        when(items.findByLearningPackageIdOrderByOrderIndex(50L)).thenReturn(activities);
        when(packages.findByLearningPathIdOrderByOrderIndex(path.getId())).thenReturn(List.of(pkg));
        stubNoScoredSessions();

        tx.executeWithoutResult(status -> service.packageDetail(userId, 50L));

        assertEquals(1, txCount(userId), "a lesson-less package is its own chapter and rewards once");
        assertEquals(5, amount(userId));
        assertEquals("PACKAGE", jdbc.queryForObject(
                "SELECT reference_type FROM user_credit_transaction WHERE user_id = ?", String.class, userId));
        assertEquals(50L, jdbc.queryForObject(
                "SELECT reference_id FROM user_credit_transaction WHERE user_id = ?", Long.class, userId));
        assertEquals(5, balance(userId));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentCompletionOfFinalSegmentCreditsTheChapterExactlyOnce() throws Exception {
        long userId = seed();
        var path = interactivePath(userId);
        var segment1 = segment(path, 11L, 1);
        var segment2 = segment(path, 12L, 2);
        segment2.setSourceLesson(segment1.getSourceLesson());
        var first = activities(segment1, 1L);
        var second = activities(segment2, 101L);
        markAllTerminal(first);
        markAllTerminal(second);
        stubChapter(userId, segment1, segment2, first, second);

        tx.executeWithoutResult(status -> service.packageDetail(userId, 11L));
        assertEquals(0, txCount(userId), "first segment must not reward before the chapter is complete");

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Throwable> errors = java.util.Collections.synchronizedList(new ArrayList<>());
        Runnable complete = () -> {
            try {
                ready.countDown();
                go.await();
                tx.executeWithoutResult(status -> service.packageDetail(userId, 12L));
            } catch (Throwable error) {
                errors.add(error);
            }
        };
        Thread firstThread = new Thread(complete, "chapter-reward-mysql-1");
        Thread secondThread = new Thread(complete, "chapter-reward-mysql-2");
        firstThread.start();
        secondThread.start();
        assertTrue(ready.await(10, TimeUnit.SECONDS), "both threads must reach the completion");
        go.countDown();
        firstThread.join(30_000);
        secondThread.join(30_000);

        assertTrue(errors.isEmpty(), "both concurrent completions must return normally: " + errors);
        assertEquals(1, txCount(userId), "two concurrent completions must credit the chapter exactly once");
        assertEquals(5, balance(userId), "two concurrent completions must leave a single 5 CP credit");
    }

    // ---- helpers ----

    private void stubChapter(long userId, LearningPathPackage segment1, LearningPathPackage segment2,
            List<UserLearningPathItem> first, List<UserLearningPathItem> second) {
        when(packages.findOwnedByIdForUpdate(11L, userId)).thenReturn(Optional.of(segment1));
        when(packages.findOwnedByIdForUpdate(12L, userId)).thenReturn(Optional.of(segment2));
        when(items.findByLearningPackageIdOrderByOrderIndex(11L)).thenReturn(first);
        when(items.findByLearningPackageIdOrderByOrderIndex(12L)).thenReturn(second);
        when(packages.findByLearningPathIdOrderByOrderIndex(segment1.getLearningPath().getId()))
                .thenReturn(List.of(segment1, segment2));
        stubNoScoredSessions();
    }

    private void stubNoScoredSessions() {
        lenient().when(quizSessions.findByActivityIdAndQuizTypeAndStatusAndPassed(
                anyLong(), anyString(), anyString(), eq(true))).thenReturn(List.of());
    }

    private long seed() {
        int seq = SEQ.incrementAndGet();
        return new TransactionTemplate(transactions).execute(status -> {
            Role role = Role.builder().name("LEARNER_CHAPTER_REWARD_MYSQL_" + seq).build();
            em.persist(role);
            User user = User.builder().role(role).email("chapter-reward-mysql-" + seq + "@test.local")
                    .loginType("LOCAL").creditBalance(0).build();
            em.persist(user);
            em.flush();
            return user.getId();
        });
    }

    private UserLearningPath interactivePath(long userId) {
        return UserLearningPath.builder().id(200L).user(User.builder().id(userId).build()).title("Pilot")
                .goal("TRAVEL").currentLevel("N5").targetLevel("N4").minutesPerDay(30)
                .experienceVersion(LearningExperienceVersion.INTERACTIVE_V2).build();
    }

    private LearningPathPackage segment(UserLearningPath path, Long id, int order) {
        return LearningPathPackage.builder().id(id).learningPath(path).phase("GOAL_LEARNING")
                .sourceLesson(Lesson.builder().id(100L + id).build()).contentRevision("revision")
                .dayNumber(order).orderIndex(order).title("Ngày " + order).estimatedMinutes(30)
                .status(LearningPackageStatus.IN_PROGRESS).build();
    }

    private LearningPathPackage warmUpPackage(UserLearningPath path, Long id, int order) {
        return LearningPathPackage.builder().id(id).learningPath(path).phase("WARM_UP")
                .dayNumber(order).orderIndex(order).title("Ôn tập điểm yếu").estimatedMinutes(5)
                .status(LearningPackageStatus.IN_PROGRESS).build();
    }

    private List<UserLearningPathItem> activities(LearningPathPackage pkg, long idOffset) {
        var types = List.of(LearningActivityType.VOCABULARY, LearningActivityType.KANJI,
                LearningActivityType.GRAMMAR, LearningActivityType.DIALOGUE);
        List<UserLearningPathItem> result = new ArrayList<>();
        for (int i = 0; i < types.size(); i++) {
            result.add(UserLearningPathItem.builder().id(idOffset + i)
                    .learningPath(pkg.getLearningPath()).learningPackage(pkg)
                    .orderIndex(i + 1).activityType(types.get(i)).activityState(LearningActivityState.LOCKED)
                    .build());
        }
        return result;
    }

    private void markAllTerminal(List<UserLearningPathItem> activities) {
        activities.get(0).setActivityState(LearningActivityState.PASSED);
        activities.get(1).setActivityState(LearningActivityState.PASSED);
        activities.get(2).setActivityState(LearningActivityState.PASSED);
        activities.get(3).setActivityState(LearningActivityState.COMPLETED);
    }

    private int txCount(long userId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM user_credit_transaction WHERE user_id = ?",
                Integer.class, userId);
    }

    private int amount(long userId) {
        return jdbc.queryForObject("SELECT amount FROM user_credit_transaction WHERE user_id = ?",
                Integer.class, userId);
    }

    private int balance(long userId) {
        return jdbc.queryForObject("SELECT credit_balance FROM users WHERE id = ?", Integer.class, userId);
    }

    private void setField(String name, Object value) {
        try {
            var field = InteractiveLearningPathServiceImpl.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(service, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static void execute(String url, String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(url, MYSQL_USER, MYSQL_PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
