package com.jlas.learning.service.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Owner 2026-10-21 (OD-13): English meanings of the Learning-owned KANJIDIC2 copy, with their credit line. */
class KanjiGlossCatalogTest {
    private final KanjiGlossCatalog catalog = new KanjiGlossCatalog();

    @Test void givesTheEnglishMeaningsOfAJlptKanjiAndNothingForOthers() {
        assertEquals("one; one radical (no.1)", catalog.meaningEn("一"));
        assertNull(catalog.meaningEn("A"));
        assertNull(catalog.meaningEn(null));
    }

    @Test void creditsKanjidic2UnderItsLicence() {
        assertTrue(catalog.attribution().contains("KANJIDIC2"), catalog.attribution());
        assertTrue(catalog.attribution().contains("CC BY-SA 4.0"), catalog.attribution());
    }
}
