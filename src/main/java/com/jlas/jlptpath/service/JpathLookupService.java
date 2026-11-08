package com.jlas.jlptpath.service;

import com.jlas.jlptpath.dto.JpathGrammarLookupView;
import java.util.List;

/** [JLAS-W0108] Look up of grammar patterns for a text selection on JLPT path pages. */
public interface JpathLookupService {

    /**
     * At most three visible patterns of released levels matching {@code q}, best score first, then level (N5 first)
     * and sort order; a blank query or one over 100 characters → empty.
     */
    List<JpathGrammarLookupView> grammar(Long userId, String q);
}
