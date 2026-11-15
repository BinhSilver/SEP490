package com.jlas.assessment.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jlas.assessment.dto.JlptExamResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JlptTimedTranscriptsTest {
    private static final String JA = "narrator: 説明です。\nwoman2: わかりました。";
    private static final String VI = "Người dẫn: Đây là lời hướng dẫn.\nNữ2: Tôi hiểu rồi.";
    private static final String URL = "/audio/JLPT/N3/N3_MOCK_TIMING_TEST/Mondai1/1.mp3";

    private JlptExamResponse.AudioClip clip(String ja, String vi, String url) {
        return new JlptExamResponse.AudioClip(123, "QUESTION", url, 0, null,
                List.of(new JlptExamResponse.TranscriptSegment(null, null, ja, null, vi)), false);
    }

    private ObjectNode entry() {
        var mapper = new ObjectMapper();
        var entry = mapper.createObjectNode().put("audioUrl", URL).put("duration", 8)
                .put("sourceJapaneseSha256", JlptTimedTranscripts.sha256(JA))
                .put("sourceVietnameseSha256", JlptTimedTranscripts.sha256(VI));
        var segments = entry.putArray("segments");
        segments.addObject().put("start", .12).put("end", 3.5).put("ja", "説明です。").put("vi", "Đây là lời hướng dẫn.");
        segments.addObject().put("start", 3.5).put("end", 8).put("ja", "わかりました。").put("vi", "Tôi hiểu rồi.");
        return entry;
    }

    @Test void measuresEachTurnWithoutChangingAudioIdentity() {
        var actual = JlptTimedTranscripts.resolveEntry(entry(), clip(JA, VI, URL));
        assertTrue(actual.transcriptAligned());
        assertEquals(123, actual.id());
        assertEquals(URL, actual.audioUrl());
        assertEquals(8d, actual.end());
        assertEquals(2, actual.transcript().size());
        assertEquals(3.5, actual.transcript().get(1).start());
        assertEquals("Tôi hiểu rồi.", actual.transcript().get(1).vi());
    }

    @Test void changedAudioOrTextInvalidatesStoredTimes() {
        for (var previous : List.of(clip(JA + "変更", VI, URL), clip(JA, VI + " sửa", URL),
                clip(JA, VI, URL + ".replaced"),
                new JlptExamResponse.AudioClip(123, "QUESTION", URL, 0, 4d,
                        clip(JA, VI, URL).transcript(), false))) {
            assertSame(previous, JlptTimedTranscripts.resolveEntry(entry(), previous));
        }
    }

    @Test void rejectsOverlappingOrOutOfRangeSegments() {
        var previous = clip(JA, VI, URL);
        var overlap = entry();
        ((ObjectNode) overlap.path("segments").get(1)).put("start", 3);
        assertSame(previous, JlptTimedTranscripts.resolveEntry(overlap, previous));
        var tooLong = entry();
        ((ObjectNode) tooLong.path("segments").get(1)).put("end", 9);
        assertSame(previous, JlptTimedTranscripts.resolveEntry(tooLong, previous));
    }

    @Test void resolvesPackagedTimingWithoutBundlingAnMp3() {
        var previous = clip(JA, VI, URL);
        var actual = new JlptLocalAudio().resolve("N3_MOCK_TIMING_TEST", 1, 1, 123, previous);
        assertTrue(actual.transcriptAligned());
        assertEquals(2, actual.transcript().size());
        assertEquals(URL, actual.audioUrl());
    }

    @Test void splitsTheCompanyEventIntoSentencesAndSeparateAlternatives() throws Exception {
        var mapper = new ObjectMapper();
        try (var input = getClass().getResourceAsStream("/jlpt/sentence-example-source.json")) {
            assertNotNull(input);
            var source = mapper.readTree(input);
            var previous = clip(source.get("ja").asText(), source.get("vi").asText(),
                    "/audio/JLPT/N3/N3_MOCK_001/Mondai3/3.mp3");
            var actual = new JlptLocalAudio().resolve("N3_MOCK_001", 3, 3, 123, previous);
            assertTrue(actual.transcriptAligned());
            assertEquals(13, actual.transcript().size());
            assertEquals("そうだね。", actual.transcript().get(2).ja());
            assertEquals("Ừ.", actual.transcript().get(2).vi());
            assertTrue(actual.transcript().get(3).ja().startsWith("体育館"));
            assertTrue(actual.transcript().get(3).start() > actual.transcript().get(2).start());
            for (int i = 0; i < 4; i++) {
                assertTrue(actual.transcript().get(9 + i).ja().startsWith("１２３４".substring(i, i + 1) + "番、"));
            }
        }
    }
}
