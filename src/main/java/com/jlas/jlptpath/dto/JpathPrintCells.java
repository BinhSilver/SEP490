package com.jlas.jlptpath.dto;

/**
 * [JLAS-W0108] Practice squares of one printed row: {@code faded} copies with the guide text, then {@code blank}
 * copies to write alone; each copy is {@code units} squares wide (one square per character, at most 10).
 */
public record JpathPrintCells(int faded, int blank, int units) {
}
