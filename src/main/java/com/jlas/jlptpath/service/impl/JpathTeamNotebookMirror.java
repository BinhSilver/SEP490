package com.jlas.jlptpath.service.impl;

import com.jlas.dictionary.kanjimap.KanjiMapNotebookService;
import com.jlas.dictionary.service.DictionaryEngagementService;
import com.jlas.jlptpath.entity.JpathKanji;
import com.jlas.jlptpath.entity.JpathVocabulary;
import com.jlas.jlptpath.enums.JpathItemType;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * [JLAS-W0108] F121 (owner 14/11 "Ghi sang sổ nhóm"): a word or kanji the learner puts in the path notebook by hand is
 * also written to the team notebook (dictionary module), in a notebook named {@value #NOTEBOOK_NAME}; taking it out of
 * the path notebook takes it out of that notebook too. Only the team's public services are called, after the path
 * transaction commits and in a transaction of its own, so a failure there never undoes the path notebook.
 */
@Slf4j
@Component
public class JpathTeamNotebookMirror {

    static final String NOTEBOOK_NAME = "Lộ trình học";

    private final JpathContentReader content;
    private final ObjectProvider<DictionaryEngagementService> words;
    private final ObjectProvider<KanjiMapNotebookService> kanji;
    private final ObjectProvider<PlatformTransactionManager> txManager;

    public JpathTeamNotebookMirror(JpathContentReader content, ObjectProvider<DictionaryEngagementService> words,
                                   ObjectProvider<KanjiMapNotebookService> kanji,
                                   ObjectProvider<PlatformTransactionManager> txManager) {
        this.content = content;
        this.words = words;
        this.kanji = kanji;
        this.txManager = txManager;
    }

    public void added(Long userId, JpathItemType type, String code) {
        mirror(userId, type, code, true);
    }

    public void removed(Long userId, JpathItemType type, String code) {
        mirror(userId, type, code, false);
    }

    private void mirror(Long userId, JpathItemType type, String code, boolean add) {
        DictionaryEngagementService w = words.getIfAvailable();
        if (w == null || userId == null) {
            return;
        }
        Long vocabularyId = type == JpathItemType.VOCAB
                ? content.vocab(code).map(JpathVocabulary::getDictionaryEntryId).orElse(null) : null;
        String character = type == JpathItemType.KANJI ? content.kanji(code).map(JpathKanji::getCharacter).orElse(null) : null;
        if (vocabularyId == null && character == null) {
            return; // no link to the dictionary: nothing to write
        }
        Runnable work = () -> run(() -> {
            Long notebookId = notebookId(w, userId);
            if (vocabularyId != null) {
                if (add) w.addToNotebook(userId, notebookId, vocabularyId);
                else w.removeFromNotebook(userId, notebookId, vocabularyId);
            } else {
                KanjiMapNotebookService k = kanji.getIfAvailable();
                if (k == null) return;
                if (add) k.save(userId, notebookId, List.of(character));
                else k.remove(userId, notebookId, character);
            }
        }, userId, code);
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

    private void run(Runnable body, Long userId, String code) {
        try {
            PlatformTransactionManager tm = txManager.getIfAvailable();
            if (tm == null) {
                body.run();
                return;
            }
            TransactionTemplate tx = new TransactionTemplate(tm);
            tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            tx.executeWithoutResult(s -> body.run());
        } catch (RuntimeException e) {
            log.warn("F121: could not mirror path notebook item {} of user {} to the team notebook: {}", code, userId,
                    e.getMessage());
        }
    }

    private static Long notebookId(DictionaryEngagementService w, Long userId) {
        return w.notebooks(userId).stream().filter(n -> NOTEBOOK_NAME.equals(n.name())).findFirst()
                .map(DictionaryEngagementService.NotebookSummary::id)
                .orElseGet(() -> w.createNotebook(userId, NOTEBOOK_NAME).id());
    }
}
