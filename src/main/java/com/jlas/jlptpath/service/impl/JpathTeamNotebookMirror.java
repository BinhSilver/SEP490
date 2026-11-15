package com.jlas.jlptpath.service.impl;

import com.jlas.jlptpath.entity.JpathKanji;
import com.jlas.jlptpath.enums.JpathItemType;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * [JLAS-W0108] F121 (owner 14/11 "Ghi sang sổ nhóm"): a kanji the learner puts in the path notebook by hand is also
 * written to the path notebook of the team notebook page; taking it out takes it out there too. F126: that is the one
 * shared notebook kept by {@link PathTeamNotebook}; words are left to {@link JpathTeamNotebookListener}, which already
 * mirrors every word entering or leaving the path notebook. Runs after the path transaction commits.
 */
@Component
public class JpathTeamNotebookMirror {

    private final JpathContentReader content;
    private final ObjectProvider<PathTeamNotebook> team;

    public JpathTeamNotebookMirror(JpathContentReader content, ObjectProvider<PathTeamNotebook> team) {
        this.content = content;
        this.team = team;
    }

    public void added(Long userId, JpathItemType type, String code) {
        mirror(userId, type, code, true);
    }

    public void removed(Long userId, JpathItemType type, String code) {
        mirror(userId, type, code, false);
    }

    private void mirror(Long userId, JpathItemType type, String code, boolean add) {
        PathTeamNotebook t = team.getIfAvailable();
        if (t == null || userId == null || type != JpathItemType.KANJI) {
            return;
        }
        String character = content.kanji(code).map(JpathKanji::getCharacter).orElse(null);
        if (character == null) {
            return; // no character: nothing to write
        }
        Runnable work = () -> {
            if (add) t.addKanji(userId, character);
            else t.removeKanji(userId, character);
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    work.run();
                }
            });
        } else {
            work.run();
        }
    }
}
