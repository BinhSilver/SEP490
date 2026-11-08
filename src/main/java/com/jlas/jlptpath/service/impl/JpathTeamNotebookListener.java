package com.jlas.jlptpath.service.impl;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.dictionary.service.DictionaryEngagementService;
import com.jlas.dictionary.service.DictionaryEngagementService.NotebookSummary;
import com.jlas.jlptpath.entity.JpathUserSettings;
import com.jlas.jlptpath.repository.JpathUserSettingsRepository;
import com.jlas.jlptpath.service.JpathNotebookChangedEvent;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * [JLAS-W0108] Mirrors JLPT path notebook words into the learner's dictionary notebook {@value #NOTEBOOK_NAME}
 * (owned by the dictionary module, used only through {@link DictionaryEngagementService}). Runs after the learner's
 * change has committed and has no transaction of its own: every step runs in its own new transaction, so a failing
 * dictionary call (whose {@code @Transactional} method marks its transaction rollback-only) never undoes an earlier
 * step. A failure is logged and stops the remaining steps; the learner's action is never affected.
 */
@Component
public class JpathTeamNotebookListener {

    static final String NOTEBOOK_NAME = "Lộ trình cá nhân";

    private static final Logger log = LoggerFactory.getLogger(JpathTeamNotebookListener.class);

    private final DictionaryEngagementService dictionary;
    private final JpathUserSettingsRepository settings;
    private final TransactionTemplate tx;

    public JpathTeamNotebookListener(DictionaryEngagementService dictionary, JpathUserSettingsRepository settings,
            PlatformTransactionManager transactionManager) {
        this.dictionary = dictionary;
        this.settings = settings;
        this.tx = new TransactionTemplate(transactionManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(JpathNotebookChangedEvent e) {
        if (e.dictionaryEntryId() == null) {
            return;
        }
        Long userId = e.userId();
        try {
            Lookup found = tx.execute(s -> find(userId));
            Long notebookId = found.notebookId();
            if (notebookId == null) {
                if (!e.added()) {
                    return; // nothing to remove from, and no reason to create an empty notebook
                }
                notebookId = create(userId);
            }
            if (!notebookId.equals(found.rememberedId())) {
                Long id = notebookId;
                tx.executeWithoutResult(s -> remember(userId, id));
            }
            Long id = notebookId;
            if (e.added()) {
                tx.executeWithoutResult(s -> dictionary.addToNotebook(userId, id, e.dictionaryEntryId()));
            } else {
                tx.executeWithoutResult(s -> dictionary.removeFromNotebook(userId, id, e.dictionaryEntryId()));
            }
        } catch (RuntimeException ex) {
            log.warn("Could not mirror path notebook change (user {}, entry {}, added {}) into the team notebook: {}",
                    userId, e.dictionaryEntryId(), e.added(), ex.toString());
        }
    }

    /** The remembered notebook if it still exists, else the learner's notebook named {@value #NOTEBOOK_NAME}. */
    private Lookup find(Long userId) {
        Long remembered = settings.findById(userId).map(JpathUserSettings::getTeamNotebookId).orElse(null);
        List<NotebookSummary> all = dictionary.notebooks(userId);
        Long id = all.stream().filter(n -> n.id().equals(remembered)).map(NotebookSummary::id).findFirst()
                .orElseGet(() -> all.stream().filter(n -> NOTEBOOK_NAME.equals(n.name())).map(NotebookSummary::id)
                        .findFirst().orElse(null));
        return new Lookup(remembered, id);
    }

    /** Creates the notebook; on a name conflict (created meanwhile) looks it up again in a new transaction. */
    private Long create(Long userId) {
        try {
            return Objects.requireNonNull(tx.execute(s -> dictionary.createNotebook(userId, NOTEBOOK_NAME))).id();
        } catch (ApiException ex) {
            if (ex.getErrorCode() != ErrorCode.CONFLICT) {
                throw ex;
            }
            Long id = tx.execute(s -> find(userId)).notebookId();
            if (id == null) {
                throw ex;
            }
            return id;
        }
    }

    private void remember(Long userId, Long notebookId) {
        JpathUserSettings row = settings.findById(userId)
                .orElseGet(() -> JpathUserSettings.builder().userId(userId).build());
        row.setTeamNotebookId(notebookId);
        settings.save(row);
    }

    private record Lookup(Long rememberedId, Long notebookId) {
    }
}
