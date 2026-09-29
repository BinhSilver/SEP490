package com.jlas.learning.service;

import com.jlas.learning.dto.ComprehensionExercise;

import java.util.List;

/** Read-only readiness boundary between the Smart Test question bank and Learning Path V2. */
public interface ComprehensionExerciseCatalog {
    CatalogResult resolve(String skill, String jlptLevel);

    record CatalogResult(List<ComprehensionExercise> exercises, List<CatalogGap> gaps) {
        public CatalogResult {
            exercises = List.copyOf(exercises);
            gaps = List.copyOf(gaps);
        }
    }

    record CatalogGap(String canonicalRef, String reasonCode) { }
}
