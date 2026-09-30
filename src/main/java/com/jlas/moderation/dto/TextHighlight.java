package com.jlas.moderation.dto;

/** A flagged term's position in a text: [start, end) in UTF-16 indexes, as JavaScript slices strings. */
public record TextHighlight(int start, int end, String term, String action) {
}
