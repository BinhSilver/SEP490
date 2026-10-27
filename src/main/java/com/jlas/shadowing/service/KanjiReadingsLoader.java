package com.jlas.shadowing.service;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Loads per-kanji readings for answer matching; best effort, matching still works without them. */
@Component
class KanjiReadingsLoader {
    private static final Logger log = LoggerFactory.getLogger(KanjiReadingsLoader.class);
    private final JdbcTemplate jdbc;

    KanjiReadingsLoader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @EventListener(ApplicationReadyEvent.class)
    void load() {
        try {
            Map<Integer, Set<String>> readings = new HashMap<>();
            jdbc.query("SELECT k.`character` AS kanji, r.reading_text AS reading FROM kanji_reading r JOIN kanji k ON k.id = r.kanji_id",
                    row -> { KanjiReadings.collect(readings, row.getString("kanji"), row.getString("reading")); });
            KanjiReadings.replace(readings);
            log.info("Loaded readings of {} kanji for mixed kanji/kana answers", KanjiReadings.size());
        } catch (RuntimeException e) {
            log.warn("Kanji readings unavailable; answers must be all kanji or all kana ({})", e.getClass().getSimpleName());
        }
    }
}
