package com.jlas.mission.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class StreakFreezeDayRepositoryConcurrencyTest {

    @Test
    void simultaneousOffersCreateExactlyOneLedgerRow() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:streak-freeze-concurrency;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=30000");
        dataSource.setUser("sa");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE streak_freeze_day (
                    user_id BIGINT NOT NULL,
                    freeze_date DATE NOT NULL,
                    status VARCHAR(20) NOT NULL,
                    streak_at_risk INT NOT NULL DEFAULT 0,
                    source VARCHAR(20),
                    offered_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
                    decided_at TIMESTAMP,
                    PRIMARY KEY (user_id, freeze_date)
                )
                """);
        StreakFreezeDayRepository repository = new StreakFreezeDayRepository(jdbc);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Boolean> results = java.util.Collections.synchronizedList(new ArrayList<>());
        List<Throwable> errors = java.util.Collections.synchronizedList(new ArrayList<>());
        LocalDate missed = LocalDate.of(2026, 10, 2);

        Runnable offer = () -> {
            try {
                ready.countDown();
                go.await();
                results.add(repository.offer(7L, missed, 9));
            } catch (Throwable error) {
                errors.add(error);
            }
        };
        Thread first = new Thread(offer, "freeze-offer-1");
        Thread second = new Thread(offer, "freeze-offer-2");
        first.start();
        second.start();
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        go.countDown();
        first.join(30_000);
        second.join(30_000);

        assertTrue(errors.isEmpty(), "both idempotent offers must return normally: " + errors);
        assertEquals(2, results.size());
        assertEquals(1L, results.stream().filter(Boolean::booleanValue).count());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM streak_freeze_day", Integer.class));
    }
}
