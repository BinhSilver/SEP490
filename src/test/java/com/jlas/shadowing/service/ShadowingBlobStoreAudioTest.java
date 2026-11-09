package com.jlas.shadowing.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ShadowingBlobStoreAudioTest {
    private final ShadowingBlobStore store = new ShadowingBlobStore(
            "AccountName=example;AccountKey=c2VjcmV0", "jlas-media", "audio");

    @Test
    void audioNamesRejectTraversalAndEncodedPaths() {
        assertTrue(ShadowingBlobStore.isSafeAudioName("JLPT/N3/Mondai1/1.mp3"));
        for (String name : new String[] {"../1.mp3", "JLPT/../1.mp3", "JLPT\\1.mp3", "%2e%2e/1.mp3"}) {
            assertFalse(ShadowingBlobStore.isSafeAudioName(name));
            assertTrue(store.signedAudioUrl(name).isEmpty());
        }
    }

    @Test
    void audioExpiryIsRoundedToTheNextHourPlusOne() {
        String name = "JLPT/N3/Mondai1/1.mp3";
        URI first = store.signedAudioUrl(name, Instant.parse("2026-11-09T10:00:00Z")).orElseThrow();
        URI sameHour = store.signedAudioUrl(name, Instant.parse("2026-11-09T10:59:59Z")).orElseThrow();
        URI nextHour = store.signedAudioUrl(name, Instant.parse("2026-11-09T11:00:00Z")).orElseThrow();
        assertEquals(first, sameHour);
        assertNotEquals(first, nextHour);
        assertTrue(first.toString().contains("se=2026-11-09T12%3A00%3A00Z"));
    }
}
