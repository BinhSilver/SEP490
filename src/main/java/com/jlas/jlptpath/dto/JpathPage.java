package com.jlas.jlptpath.dto;

import java.util.List;
import java.util.Map;

/**
 * [JLAS-W0108] One page of a JLPT path list. {@code page} is 0-based, {@code size} is the page size (50),
 * {@code total} counts every matching row; {@code facets} maps a facet name (e.g. {@code topics}, {@code states},
 * {@code radicals}) to its values, each counted with every other filter applied.
 */
public record JpathPage<T>(List<T> items, int page, int size, long total, Map<String, List<JpathFacet>> facets) {
}
