package com.jlas.jlptpath.service.impl;

import com.jlas.jlptpath.service.JpathNotebookChangedEvent;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * [JLAS-W0108] Mirrors JLPT path notebook words (put there by hand or by the path) into the learners' path notebook of
 * the team notebook page, after the learner's change has committed. Since F126 that is the one shared notebook kept by
 * {@link PathTeamNotebook} ("Lộ trình học", also used by the Comm path); failures are logged there, never thrown.
 */
@Component
public class JpathTeamNotebookListener {

    private final PathTeamNotebook team;

    public JpathTeamNotebookListener(PathTeamNotebook team) {
        this.team = team;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(JpathNotebookChangedEvent e) {
        if (e.dictionaryEntryId() == null) {
            return;
        }
        if (e.added()) {
            team.addWords(e.userId(), List.of(e.dictionaryEntryId()));
        } else {
            team.removeWords(e.userId(), List.of(e.dictionaryEntryId()));
        }
    }
}
