package com.jlas.jlptpath.dto;

import java.util.List;

/** [JLAS-W0108] Board state after a pick: verdict, wrong picks so far, whether every pair is matched. */
public record JpathPairPickResult(boolean correct, int errors, boolean boardComplete, List<String> matchedLeftIds) {
}
