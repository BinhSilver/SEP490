package com.jlas.learning.service.impl;

import com.jlas.assessment.entity.AssessmentQuestion;
import com.jlas.assessment.repository.AssessmentQuestionRepository;
import com.jlas.learning.dto.ComprehensionExercise;
import com.jlas.learning.dto.ComprehensionQuestionSpec;
import com.jlas.learning.service.ComprehensionExerciseCatalog;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** [JLAS-W0193] Builds exercises only from active, exact-level canonical bank rows. */
@Service
@RequiredArgsConstructor
public class JpaComprehensionExerciseCatalog implements ComprehensionExerciseCatalog {
    private static final List<String> SUPPORTED_SKILLS = List.of("READING", "LISTENING");
    private final AssessmentQuestionRepository questions;

    @Override
    public CatalogResult resolve(String skill, String jlptLevel) {
        String normalizedSkill = normalizeToken(skill);
        String normalizedLevel = normalizeToken(jlptLevel);
        if (!SUPPORTED_SKILLS.contains(normalizedSkill)) {
            throw new IllegalArgumentException("Comprehension skill must be READING or LISTENING.");
        }

        List<CatalogGap> gaps = new ArrayList<>();
        Map<String, Group> groups = new LinkedHashMap<>();
        for (AssessmentQuestion row : questions
                .findByIsActiveTrueAndSkillAndJlptLevelOrderByIdAsc(normalizedSkill, normalizedLevel)) {
            if (!Boolean.TRUE.equals(row.getIsActive()) || !normalizedSkill.equals(normalizeToken(row.getSkill()))
                    || !normalizedLevel.equals(normalizeToken(row.getJlptLevel()))) {
                gaps.add(gap(row, "LEVEL_MISMATCH"));
                continue;
            }
            String source = normalizedSkill.equals("READING")
                    ? sanitizePassage(row.getPassageHtml()) : normalizeAudioUrl(row.getAudioUrl());
            if (source == null) {
                gaps.add(gap(row, normalizedSkill.equals("READING") ? "PASSAGE_MISSING" : "AUDIO_MISSING"));
                continue;
            }
            String outcome = row.getOutcome() == null ? null : trimToNull(row.getOutcome().getCode());
            String topic = row.getTopicTag() == null ? null : trimToNull(row.getTopicTag().getCode());
            if (outcome == null && topic == null) {
                gaps.add(gap(row, "SOURCE_AMBIGUOUS"));
                continue;
            }
            ComprehensionQuestionSpec question = toQuestion(row);
            if (question == null) {
                gaps.add(gap(row, "QUESTION_INVALID"));
                continue;
            }
            String identity = nullSafe(outcome) + "|" + nullSafe(topic);
            String fingerprint = sha256(normalizedSkill + "|" + normalizedLevel + "|" + identity + "|" + source);
            groups.computeIfAbsent(fingerprint,
                    ignored -> new Group(fingerprint, source, outcome, topic, new ArrayList<>())).questions.add(question);
        }

        List<ComprehensionExercise> exercises = new ArrayList<>();
        groups.values().stream().sorted(Comparator.comparing(Group::fingerprint)).forEach(group -> {
            group.questions.sort(Comparator.comparing(ComprehensionQuestionSpec::id));
            if (group.questions.size() < 2) {
                gaps.add(new CatalogGap(group.fingerprint, "QUESTION_COUNT_INSUFFICIENT"));
                return;
            }
            String revisionMaterial = group.fingerprint + group.questions.stream()
                    .map(this::revisionPart).reduce("", (left, right) -> left + "|" + right);
            exercises.add(new ComprehensionExercise(group.fingerprint, normalizedSkill, normalizedLevel,
                    group.source, group.outcome, group.topic, group.questions, sha256(revisionMaterial)));
        });
        gaps.sort(Comparator.comparing(CatalogGap::canonicalRef).thenComparing(CatalogGap::reasonCode));
        return new CatalogResult(exercises, gaps);
    }

    private ComprehensionQuestionSpec toQuestion(AssessmentQuestion row) {
        if (!"MULTIPLE_CHOICE".equals(normalizeToken(row.getQuestionType()))) return null;
        String prompt = trimToNull(row.getQuestionText());
        if (prompt == null) return null;
        List<String> options = new ArrayList<>();
        for (String raw : List.of(nullSafe(row.getOption1()), nullSafe(row.getOption2()),
                nullSafe(row.getOption3()), nullSafe(row.getOption4()))) {
            String option = trimToNull(raw);
            if (option != null) options.add(option);
        }
        if (options.size() < 2 || options.size() > 4 || row.getCorrectOption() == null
                || row.getCorrectOption() < 1 || row.getCorrectOption() > options.size()) return null;
        long distinct = options.stream().map(this::normalizeAnswer).distinct().count();
        if (distinct != options.size()) return null;
        return new ComprehensionQuestionSpec(row.getId(), prompt, options, row.getCorrectOption(), row.getExplanation());
    }

    private String revisionPart(ComprehensionQuestionSpec question) {
        return question.id() + ":" + question.prompt() + ":" + String.join("\u001f", question.options())
                + ":" + question.correctOption() + ":" + nullSafe(question.explanation());
    }

    private CatalogGap gap(AssessmentQuestion row, String code) {
        return new CatalogGap(row.getId() == null ? "QUESTION:UNKNOWN" : "QUESTION:" + row.getId(), code);
    }

    /** [JLAS-W0193] RC Task 8 hardening finding — entities must be decoded BEFORE tag-stripping, never after.
     * The previous order stripped literal {@code <...>} tags first and only unescaped HTML entities afterward,
     * so a canonical row whose passage contained an entity-encoded tag (e.g. {@code &lt;script&gt;...
     * &lt;/script&gt;}) sailed through both stripping regexes untouched — they only match literal {@code <} —
     * and only became a real, literal tag once unescaped at the end, reappearing in the value this method
     * returns as "sanitized". The one current consumer (interactive-comprehension-study.js) independently
     * HTML-escapes this value again before insertion, so this was not end-to-end exploitable through today's
     * only caller, but the catalog's own contract requires the returned passage to be safe on its own. */
    private String sanitizePassage(String html) {
        String value = trimToNull(html);
        if (value == null) return null;
        value = HtmlUtils.htmlUnescape(value);
        value = value.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ")
                .replaceAll("(?s)<[^>]+>", " ");
        return trimToNull(normalizeSpace(value));
    }

    /** Validation is local and side-effect free: the catalog never fetches learner-controlled media. */
    private String normalizeAudioUrl(String raw) {
        String value = trimToNull(raw);
        if (value == null) return null;
        try {
            URI uri = URI.create(value).normalize();
            if (!uri.isAbsolute()) return value.startsWith("/") && !value.startsWith("//") ? uri.toString() : null;
            String scheme = uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!("http".equals(scheme) || "https".equals(scheme)) || uri.getHost() == null
                    || uri.getUserInfo() != null) return null;
            return new URI(scheme, null, uri.getHost().toLowerCase(Locale.ROOT), uri.getPort(),
                    uri.getPath(), uri.getQuery(), null).toString();
        } catch (Exception ignored) {
            return null;
        }
    }

    private String normalizeAnswer(String value) {
        return normalizeSpace(value).toLowerCase(Locale.ROOT);
    }

    private static String normalizeToken(String value) {
        return value == null ? "" : value.strip().toUpperCase(Locale.ROOT);
    }

    private static String normalizeSpace(String value) {
        return value == null ? "" : value.strip().replaceAll("\\s+", " ");
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) return null;
        return value.strip();
    }

    private static String nullSafe(String value) {
        return Objects.toString(value, "");
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record Group(String fingerprint, String source, String outcome, String topic,
            List<ComprehensionQuestionSpec> questions) { }
}
