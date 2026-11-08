package com.jlas.jlptpath.dto;

/** [JLAS-W0108] One value of a list facet: filter value, label to show, and how many rows have it. */
public record JpathFacet(String value, String label, long count) {
}
