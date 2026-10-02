package com.jlas.moderation.dto;

import java.util.List;

/** A proposed dictionary entry plus existing published entries with the same spelling. */
public record ContributionDetail(Long id, String expression, String reading, String meaning,
                                 String wordType, String jlptLevel, String exampleJa, String exampleVi,
                                 String status, List<Existing> duplicates) {

    public record Existing(Long id, String expression, String reading, String meaning) {
    }
}
