package com.jlas.assessment.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jlas.assessment.dto.JlptExamResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.springframework.core.io.ClassPathResource;

/** Adds measured timing to matching published clips without packaging their MP3 files. */
final class JlptTimedTranscripts {
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, JsonNode> exams = new ConcurrentHashMap<>();

    JlptExamResponse.AudioClip resolve(String exam, int mondai, int question,
            JlptExamResponse.AudioClip previous) {
        if (previous == null) return null;
        JsonNode document = exams.computeIfAbsent(exam, this::load);
        JsonNode entry = document.path("questions").path(mondai + ":" + question);
        if (entry.isMissingNode()) return null;
        return resolveEntry(entry, previous);
    }

    static JlptExamResponse.AudioClip resolveEntry(JsonNode entry,
            JlptExamResponse.AudioClip previous) {
        String ja = previous.transcript().stream().map(s -> s.ja() == null ? "" : s.ja())
                .collect(Collectors.joining("\n"));
        String vi = previous.transcript().stream().map(s -> s.vi() == null ? "" : s.vi())
                .collect(Collectors.joining("\n"));
        // An editor changing the audio or either script invalidates the timing.
        if (!previous.audioUrl().equals(entry.path("audioUrl").asText())
                || previous.start() != 0
                || !sha256(ja).equals(entry.path("sourceJapaneseSha256").asText())
                || !sha256(vi).equals(entry.path("sourceVietnameseSha256").asText())) return previous;
        double duration = entry.path("duration").asDouble();
        if (!Double.isFinite(duration) || duration <= 0 || !entry.path("segments").isArray()) return previous;
        if (previous.end() != null && Math.abs(previous.end() - duration) > .001) return previous;
        var segments = new ArrayList<JlptExamResponse.TranscriptSegment>();
        double lastEnd = 0;
        for (JsonNode segment : entry.path("segments")) {
            double start = segment.path("start").asDouble(-1), end = segment.path("end").asDouble(-1);
            if (!Double.isFinite(start) || !Double.isFinite(end) || start < lastEnd
                    || end <= start || end > duration + .001 || segment.path("ja").asText().isBlank()) return previous;
            segments.add(new JlptExamResponse.TranscriptSegment(start, end,
                    segment.path("ja").asText(), null, segment.path("vi").asText(null)));
            lastEnd = end;
        }
        if (segments.isEmpty()) return previous;
        return new JlptExamResponse.AudioClip(previous.id(), previous.type(), previous.audioUrl(),
                0, duration, java.util.List.copyOf(segments), true);
    }

    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private JsonNode load(String exam) {
        var resource = new ClassPathResource("static/JLPT/timed-transcripts/" + exam + ".json");
        if (!resource.exists()) return mapper.createObjectNode();
        try (var input = resource.getInputStream()) {
            JsonNode document = mapper.readTree(input);
            if (!exam.equals(document.path("exam").asText()) || document.path("version").asInt() != 1)
                throw new IOException("Timed transcript exam/version mismatch");
            return document;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read JLPT timed transcript: " + exam, e);
        }
    }
}
