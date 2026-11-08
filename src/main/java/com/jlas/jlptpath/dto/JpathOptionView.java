package com.jlas.jlptpath.dto;

/** [JLAS-W0108] One choice shown to the learner. {@code id} is only o1.., l1.. or r1.. — never an item code. */
public record JpathOptionView(String id, String label) {
}
