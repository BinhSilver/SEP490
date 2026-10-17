package com.jlas.dictionary.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jlas.auth.entity.Role;
import com.jlas.auth.entity.User;
import com.jlas.auth.repository.UserRepository;
import com.jlas.dictionary.dto.DictionaryFavoriteResponse;
import com.jlas.dictionary.entity.VocabularyEntry;
import com.jlas.dictionary.repository.DictionaryFavoriteRepository;
import com.jlas.dictionary.repository.DictionarySearchHistoryRepository;
import com.jlas.dictionary.repository.VocabularyEntryRepository;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@DataJpaTest(properties = {
        "spring.profiles.include=",
        "spring.datasource.url=jdbc:h2:mem:dictionary-favorite-concurrency;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=30000",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@ContextConfiguration(classes = DictionaryFavoriteConcurrencyTest.Cfg.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class DictionaryFavoriteConcurrencyTest {

    @Configuration(proxyBeanMethods = false)
    @EntityScan("com.jlas")
    @EnableJpaRepositories(basePackages = {"com.jlas.dictionary.repository", "com.jlas.auth.repository"})
    static class Cfg {
        @Bean DictionaryEngagementService service(DictionaryFavoriteRepository favorites,
                DictionarySearchHistoryRepository history, VocabularyEntryRepository vocabulary,
                UserRepository users) {
            return new DictionaryEngagementService(null, favorites, history, vocabulary, users);
        }
    }

    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager transactions;
    @Autowired DictionaryEngagementService service;
    @Autowired DictionaryFavoriteRepository favorites;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentQuickSaveCreatesOneFavoriteAndBothRequestsSucceed() throws Exception {
        long[] ids = seedCommitted();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        List<DictionaryFavoriteResponse> results = java.util.Collections.synchronizedList(new ArrayList<>());
        List<Throwable> errors = java.util.Collections.synchronizedList(new ArrayList<>());

        Runnable save = () -> {
            try {
                ready.countDown();
                go.await();
                results.add(service.saveFavorite(ids[0], ids[1]));
            } catch (Throwable error) {
                errors.add(error);
            }
        };
        Thread first = new Thread(save, "favorite-save-1");
        Thread second = new Thread(save, "favorite-save-2");
        first.start();
        second.start();
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        go.countDown();
        first.join(30_000);
        second.join(30_000);

        assertTrue(errors.isEmpty(), "both idempotent requests must succeed: " + errors);
        assertEquals(2, results.size());
        assertEquals(1L, results.stream().filter(DictionaryFavoriteResponse::created).count());
        assertEquals(1L, favorites.count());
    }

    private long[] seedCommitted() {
        return new TransactionTemplate(transactions).execute(status -> {
            Role role = Role.builder().name("LEARNER_DICTIONARY_CONCURRENCY").build();
            em.persist(role);
            User user = User.builder().role(role).email("dictionary-concurrency@test.local")
                    .loginType("LOCAL").build();
            em.persist(user);
            VocabularyEntry word = VocabularyEntry.builder().expression("旅行")
                    .normalizedExpression("旅行").reading("りょこう").normalizedReading("りょこう")
                    .meaning("du lịch").build();
            em.persist(word);
            em.flush();
            return new long[]{user.getId(), word.getId()};
        });
    }
}
