package com.jlas.jlptpath.service.impl;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.dictionary.kanjimap.KanjiMapNotebookService;
import com.jlas.dictionary.service.DictionaryEngagementService;
import com.jlas.dictionary.service.DictionaryEngagementService.NotebookSummary;
import com.jlas.dictionary.util.DictionaryUtil;
import com.jlas.jlptpath.entity.JpathUserSettings;
import com.jlas.jlptpath.repository.JpathUserSettingsRepository;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * [JLAS-W0108] F126: the one notebook of the learning paths inside the team notebook page ({@code /learning/notebook},
 * next to "Sổ tay điểm yếu"). Words of the JLPT path notebook, kanji saved there by hand and words of the Comm path
 * notebook all go to it; leaving a path notebook takes the word out unless another path notebook still holds it.
 *
 * <p>Which notebook is ours: the id kept in {@code jpath_user_settings.team_notebook_id} — never a name, since the
 * learner may have a notebook of their own called {@value #NAME} (the team keeps names unique per user). A new
 * notebook is called {@value #NAME}, or {@value #NAME} (2), (3)… when the learner already uses the name. The notebook
 * of the first version ({@value #OLD_NAME}, same remembered id) is kept and renamed to {@value #NAME} when that name
 * is free. If the learner deletes it, the next added word makes a new one.</p>
 *
 * <p>Only the dictionary module's public services are called, each step in a transaction of its own; every failure
 * is logged and never reaches the learner's action.</p>
 */
@Slf4j
@Component
public class PathTeamNotebook {

    public static final String NAME = "Lộ trình học";
    static final String OLD_NAME = "Lộ trình cá nhân";
    private static final int MAX_SUFFIX = 50;

    private final ObjectProvider<DictionaryEngagementService> dictionary;
    private final ObjectProvider<KanjiMapNotebookService> kanji;
    private final JpathUserSettingsRepository settings;
    private final ObjectProvider<PlatformTransactionManager> txManager;
    private final JdbcTemplate jdbc;

    public PathTeamNotebook(ObjectProvider<DictionaryEngagementService> dictionary,
                            ObjectProvider<KanjiMapNotebookService> kanji, JpathUserSettingsRepository settings,
                            ObjectProvider<PlatformTransactionManager> txManager, JdbcTemplate jdbc) {
        this.dictionary = dictionary;
        this.kanji = kanji;
        this.settings = settings;
        this.txManager = txManager;
        this.jdbc = jdbc;
    }

    /** Makes the notebook exist (path notebook page opened) so it shows before the first word; cheap once made. */
    public void ensure(Long userId) {
        guarded(userId, "ensure", () -> {
            boolean known = settings.findById(userId).map(JpathUserSettings::getTeamNotebookId).isPresent();
            if (!known) {
                notebookId(userId, true);
            }
        });
    }

    public void addWords(Long userId, Collection<Long> vocabularyIds) {
        guarded(userId, "add words", () -> {
            if (vocabularyIds.isEmpty()) return;
            Long id = notebookId(userId, true);
            for (Long v : vocabularyIds) {
                run(() -> dictionary().addToNotebook(userId, id, v));
            }
        });
    }

    public void removeWords(Long userId, Collection<Long> vocabularyIds) {
        guarded(userId, "remove words", () -> {
            if (vocabularyIds.isEmpty()) return;
            Long id = notebookId(userId, false);
            if (id == null) return; // nothing to take out of, and no reason to make an empty notebook
            for (Long v : vocabularyIds) {
                if (!wordHeldByAPath(userId, v)) {
                    run(() -> dictionary().removeFromNotebook(userId, id, v));
                }
            }
        });
    }

    public void addKanji(Long userId, String character) {
        withKanji(userId, true, k -> k.save(userId, notebookId(userId, true), List.of(character)));
    }

    public void removeKanji(Long userId, String character) {
        withKanji(userId, false, k -> {
            Long id = notebookId(userId, false);
            if (id != null) k.remove(userId, id, character);
        });
    }

    /**
     * Our notebook's id, made (and remembered) when missing and {@code create}; {@code null} otherwise. The settings row
     * is locked meanwhile so two paths writing at once cannot make two notebooks; one retry covers a learner making a
     * notebook with the same name at that very moment.
     */
    Long notebookId(Long userId, boolean create) {
        try {
            return call(() -> resolve(userId, create));
        } catch (RuntimeException e) {
            if (e instanceof ApiException a && a.getErrorCode() != ErrorCode.CONFLICT) throw e;
            // a same-name notebook made a moment ago, or the settings row inserted by a parallel call
            return call(() -> resolve(userId, create));
        }
    }

    private Long resolve(Long userId, boolean create) {
        DictionaryEngagementService d = dictionary();
        JpathUserSettings row = settings.lockById(userId).orElse(null);
        Long remembered = row == null ? null : row.getTeamNotebookId();
        List<NotebookSummary> all = d.notebooks(userId);
        NotebookSummary ours = remembered == null ? null
                : all.stream().filter(n -> n.id().equals(remembered)).findFirst().orElse(null);
        if (ours != null) {
            if (OLD_NAME.equals(ours.name()) && all.stream().noneMatch(n -> NAME.equals(n.name()))) {
                d.renameNotebook(userId, ours.id(), NAME);
            }
            return ours.id();
        }
        if (!create) {
            return null;
        }
        Long id = Objects.requireNonNull(d.createNotebook(userId, notebookName(all))).id();
        if (row == null) {
            row = JpathUserSettings.builder().userId(userId).build();
        }
        row.setTeamNotebookId(id);
        settings.save(row);
        return id;
    }

    /** {@value #NAME}, or the first free {@value #NAME} (n) when the learner already has a notebook with the name. */
    String notebookName(List<NotebookSummary> all) {
        for (int n = 1; n <= MAX_SUFFIX; n++) {
            String name = n == 1 ? NAME : NAME + " (" + n + ")";
            if (all.stream().noneMatch(s -> name.equals(s.name()))) {
                return name;
            }
        }
        throw new ApiException(ErrorCode.CONFLICT, "Tên sổ tay đã tồn tại.");
    }

    /** True when the JLPT path notebook or a Comm path notebook of the learner still holds this dictionary word. */
    public boolean wordHeldByAPath(Long userId, Long vocabularyId) {
        return heldByJlpt(userId, vocabularyId) || heldByComm(userId, vocabularyId);
    }

    private boolean heldByJlpt(Long userId, Long vocabularyId) {
        try {
            Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM jpath_user_item u JOIN jpath_vocabulary v "
                    + "ON v.code = u.item_code WHERE u.user_id = ? AND u.item_type = 'VOCAB' AND u.state = 'NOTEBOOK' "
                    + "AND v.dictionary_entry_id = ?", Integer.class, userId, vocabularyId);
            return n != null && n > 0;
        } catch (DataAccessException e) {
            return false;
        }
    }

    /** Comm words carry no dictionary id: they match by the normalized spelling (and reading when given). */
    private boolean heldByComm(Long userId, Long vocabularyId) {
        try {
            List<Map<String, Object>> entry = jdbc.queryForList("SELECT normalized_expression e, normalized_reading r "
                    + "FROM vocabulary_entry WHERE id = ?", vocabularyId);
            if (entry.isEmpty()) return false;
            String expression = String.valueOf(entry.get(0).get("e"));
            String reading = String.valueOf(entry.get(0).get("r"));
            return jdbc.queryForList("SELECT i.ja, i.reading FROM cpath_user_note n "
                            + "JOIN cpath_user_path p ON p.id = n.path_id JOIN cpath_item i ON i.code = n.item_code "
                            + "WHERE p.user_id = ? AND n.kind = 'ITEM' AND i.kind = 'WORD'", userId).stream()
                    .anyMatch(w -> expression.equals(DictionaryUtil.normalizeReading((String) w.get("ja")))
                            && (w.get("reading") == null
                                || reading.equals(DictionaryUtil.normalizeReading((String) w.get("reading")))));
        } catch (DataAccessException e) {
            return false;
        }
    }

    private void withKanji(Long userId, boolean add, Consumer<KanjiMapNotebookService> body) {
        KanjiMapNotebookService k = kanji.getIfAvailable();
        if (k == null) return;
        guarded(userId, add ? "add kanji" : "remove kanji", () -> run(() -> body.accept(k)));
    }

    private DictionaryEngagementService dictionary() {
        DictionaryEngagementService d = dictionary.getIfAvailable();
        if (d == null) throw new IllegalStateException("dictionary module not available");
        return d;
    }

    private void guarded(Long userId, String what, Runnable body) {
        if (userId == null) return;
        try {
            body.run();
        } catch (RuntimeException e) {
            log.warn("F126: could not {} in the path notebook of user {}: {}", what, userId, e.toString());
        }
    }

    private void run(Runnable body) {
        call(() -> {
            body.run();
            return null;
        });
    }

    /** Runs the body in a new transaction of its own (straight away when there is no transaction manager). */
    private <T> T call(Supplier<T> body) {
        PlatformTransactionManager tm = txManager.getIfAvailable();
        if (tm == null) return body.get();
        TransactionTemplate tx = new TransactionTemplate(tm);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return tx.execute(s -> body.get());
    }
}
