package com.jlas.jlptpath.service;

/** [JLAS-W0108] Chooses between the JLPT path and the old learning path for a learner. */
public interface JpathEntryRouter {

    /** {@code userId} may be null (no signed-in learner), which is always {@link JpathEntryRoute#LEGACY}. */
    JpathEntryRoute route(Long userId);
}
