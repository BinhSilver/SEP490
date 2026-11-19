package com.jlas.cpath;

import com.jlas.dictionary.util.DictionaryUtil;
import com.jlas.jlptpath.service.impl.PathTeamNotebook;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * [JLAS-W0108] F126: words of the Comm path notebook ({@code cpath_user_note}) go to the learners' path notebook
 * "Lộ trình học" of the team notebook page, the same one the JLPT path writes to ({@link PathTeamNotebook}). A missed
 * word (it goes in, or counts again) is added; a right answer (it leaves) takes it out unless a path notebook still
 * holds it. Only WORD items with a published dictionary entry are written; phrases and learner lines are not words.
 *
 * <p>Comm words carry no dictionary id, so they are matched like the dictionary import does:
 * {@code normalized_expression} = katakana→hiragana(NFKC(ja)) and {@code normalized_reading} from the reading (or ja).
 * Changes of one transaction are written once after it commits, each in a transaction of its own; failures are only
 * logged, so the learner's review or weekly test never fails because of the team notebook.</p>
 */
@Slf4j
@Component
public class CpathTeamNotebookMirror {

    private static final Pattern LEARNER_LINE = Pattern.compile(".*-L\\d+$");

    private final JdbcTemplate db;
    private final ObjectProvider<PathTeamNotebook> team;

    public CpathTeamNotebookMirror(JdbcTemplate db, ObjectProvider<PathTeamNotebook> team) {
        this.db = db;
        this.team = team;
    }

    /** The Comm notebook page was opened: the shared notebook shows on /learning/notebook from now on. */
    public void ensure(Long userId) {
        PathTeamNotebook t = team.getIfAvailable();
        if (t != null && userId != null) {
            t.ensure(userId);
        }
    }

    /** One answer of a review or weekly test: wrong puts the item in the Comm notebook, right takes it out. */
    public void answered(Long userId, String itemCode, boolean correct) {
        if (userId == null || itemCode == null || LEARNER_LINE.matcher(itemCode).matches()) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            write(userId, Map.of(itemCode, !correct));
            return;
        }
        Pending pending = (Pending) TransactionSynchronizationManager.getResource(this);
        if (pending == null) {
            pending = new Pending();
            TransactionSynchronizationManager.bindResource(this, pending);
            TransactionSynchronizationManager.registerSynchronization(pending);
        }
        pending.byUser.computeIfAbsent(userId, u -> new LinkedHashMap<>()).put(itemCode, !correct);
    }

    /** Writes the last change of each item: {@code true} = add, {@code false} = remove. */
    private void write(Long userId, Map<String, Boolean> changes) {
        PathTeamNotebook t = team.getIfAvailable();
        if (t == null || changes.isEmpty()) {
            return;
        }
        try {
            String marks = String.join(",", Collections.nCopies(changes.size(), "?"));
            List<Map<String, Object>> words = db.queryForList("SELECT code, ja, reading FROM cpath_item "
                    + "WHERE kind = 'WORD' AND code IN (" + marks + ")", changes.keySet().toArray());
            Map<String, Map<String, Object>> byCode = new LinkedHashMap<>();
            words.forEach(w -> byCode.put(String.valueOf(w.get("code")), w));
            List<Long> add = new ArrayList<>();
            List<Long> remove = new ArrayList<>();
            changes.forEach((code, isAdd) -> {
                Map<String, Object> w = byCode.get(code);
                Long entry = w == null ? null : entryId((String) w.get("ja"), (String) w.get("reading"));
                if (entry != null && !add.contains(entry) && !remove.contains(entry)) {
                    (isAdd ? add : remove).add(entry);
                }
            });
            if (!add.isEmpty()) t.addWords(userId, add);
            if (!remove.isEmpty()) t.removeWords(userId, remove);
        } catch (RuntimeException e) {
            log.warn("F126: could not mirror Comm notebook items {} of user {}: {}", changes.keySet(), userId, e.toString());
        }
    }

    /** The published dictionary entry of a Comm word, or {@code null}. */
    Long entryId(String ja, String reading) {
        if (ja == null || ja.isBlank()) return null;
        String expression = DictionaryUtil.normalizeReading(ja);
        String key = DictionaryUtil.normalizeReading(reading != null && !reading.isBlank() ? reading : ja);
        List<Long> ids = db.queryForList("SELECT id FROM vocabulary_entry WHERE normalized_expression = ? "
                + "AND normalized_reading = ? AND dictionary_status = 'PUBLISHED' ORDER BY id LIMIT 1",
                Long.class, expression, key);
        if (ids.isEmpty() && (reading == null || reading.isBlank())) {
            ids = db.queryForList("SELECT id FROM vocabulary_entry WHERE normalized_expression = ? "
                    + "AND dictionary_status = 'PUBLISHED' ORDER BY id LIMIT 1", Long.class, expression);
        }
        return ids.isEmpty() ? null : ids.get(0);
    }

    /** Changes of the running transaction, written once it commits. */
    private final class Pending implements TransactionSynchronization {
        private final Map<Long, Map<String, Boolean>> byUser = new LinkedHashMap<>();

        @Override
        public void afterCommit() {
            byUser.forEach(CpathTeamNotebookMirror.this::write);
        }

        @Override
        public void afterCompletion(int status) {
            TransactionSynchronizationManager.unbindResourceIfPossible(CpathTeamNotebookMirror.this);
        }
    }
}
