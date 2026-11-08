package com.jlas.jlptpath.service.impl;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;

/**
 * [JLAS-W0108] Read-only count of the older learning module's paths still ACTIVE (admin report). A native count, so
 * the learning module's code stays untouched.
 */
@Component
public class JpathLegacyPathCounter {

    private final EntityManager em;

    public JpathLegacyPathCounter(EntityManager em) {
        this.em = em;
    }

    public long activePaths() {
        Object n = em.createNativeQuery("select count(*) from user_learning_path where status = 'ACTIVE'")
                .getSingleResult();
        return ((Number) n).longValue();
    }
}
