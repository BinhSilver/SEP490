package com.jlas.jlptpath.util;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * [JLAS-W0108] Marks that the current transaction holds the learner's path lock ({@code SELECT … FOR UPDATE} on
 * jpath_user_path), so code that must not run under it (the daily-plan insert, which opens its own transaction and
 * takes the same lock) can fail fast instead of waiting for the lock timeout. The mark is a transaction resource:
 * it is set by {@code JpathUserPathRepository.findActiveForUpdate}, survives the suspension of the transaction by a
 * REQUIRES_NEW call (so the inner call still sees it) and is cleared when the transaction completes.
 */
public final class JpathPathLocks {

    private static final Object KEY = JpathPathLocks.class;

    private JpathPathLocks() {
    }

    /** Records that the current transaction now holds the path lock; outside a transaction nothing happens. */
    public static void markHeld() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()
                || TransactionSynchronizationManager.hasResource(KEY)) {
            return;
        }
        TransactionSynchronizationManager.bindResource(KEY, Boolean.TRUE);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                TransactionSynchronizationManager.unbindResourceIfPossible(KEY);
            }
        });
    }

    /** True when the current (or a suspended outer) transaction holds the path lock. */
    public static boolean held() {
        return TransactionSynchronizationManager.hasResource(KEY);
    }

    /** Clears the mark (used by a writer that took the lock itself, at its end). */
    public static void release() {
        TransactionSynchronizationManager.unbindResourceIfPossible(KEY);
    }
}
