package com.jlas.learning.service.impl;

import com.jlas.assessment.entity.AssessmentQuestion;
import com.jlas.assessment.repository.AssessmentQuestionRepository;
import com.jlas.learning.dto.ComprehensionExercise;
import com.jlas.learning.dto.ComprehensionQuestionSpec;
import com.jlas.learning.service.ComprehensionExerciseCatalog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private static final ObjectMapper CONTEXT_MAPPER = new ObjectMapper();
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
            String transcript = normalizedSkill.equals("LISTENING") ? displayPassage(row.getPassageHtml()) : null;
            if (normalizedSkill.equals("LISTENING") && transcript == null) {
                gaps.add(gap(row, "TRANSCRIPT_MISSING"));
                continue;
            }
            String outcome = row.getOutcome() == null ? null : trimToNull(row.getOutcome().getCode());
            String topic = row.getTopicTag() == null ? null : trimToNull(row.getTopicTag().getCode());
            String ownedSourceGroup = ownedLearningGroup(row.getSourceCode(), normalizedSkill, normalizedLevel);
            if (outcome == null && topic == null && ownedSourceGroup == null) {
                gaps.add(gap(row, "SOURCE_AMBIGUOUS"));
                continue;
            }
            ComprehensionQuestionSpec question = toQuestion(row);
            if (question == null) {
                gaps.add(gap(row, "QUESTION_INVALID"));
                continue;
            }
            String identity = ownedSourceGroup == null
                    ? nullSafe(outcome) + "|" + nullSafe(topic) : ownedSourceGroup;
            String fingerprint = sha256(normalizedSkill + "|" + normalizedLevel + "|" + identity + "|" + source);
            String playbackSource = minnaPlaybackSource(row, normalizedLevel, source, ownedSourceGroup);
            if (playbackSource == null) {
                gaps.add(gap(row, "LOCAL_AUDIO_MAPPING_INVALID"));
                continue;
            }
            String display = normalizedSkill.equals("READING") ? displayPassage(row.getPassageHtml()) : playbackSource;
            Group group = groups.computeIfAbsent(fingerprint,
                    ignored -> new Group(fingerprint, playbackSource, display, transcript, outcome, topic, ownedSourceGroup,
                            new ArrayList<>()));
            if (!Objects.equals(group.source, playbackSource)) {
                gaps.add(gap(row, "AUDIO_SOURCE_MISMATCH"));
                continue;
            }
            if (normalizedSkill.equals("LISTENING") && !Objects.equals(group.transcript, transcript)) {
                gaps.add(gap(row, "TRANSCRIPT_MISMATCH"));
                continue;
            }
            group.questions.add(question);
        }

        List<ComprehensionExercise> exercises = new ArrayList<>();
        groups.values().stream().sorted(Comparator.comparing(Group::fingerprint)).forEach(group -> {
            group.questions.sort(Comparator.comparing(ComprehensionQuestionSpec::id));
            // One source may legitimately contain one question with multiple distinct answers.
            // toQuestion() validates the answer/options; requiring two questions here would
            // discard otherwise usable passages and exact one-clip listening exercises.
            String revisionMaterial = group.fingerprint + "|" + nullSafe(group.transcript) + group.questions.stream()
                    .map(this::revisionPart).reduce("", (left, right) -> left + "|" + right);
            exercises.add(new ComprehensionExercise(group.fingerprint, normalizedSkill, normalizedLevel,
                    group.source, group.outcome, group.topic, group.questions, sha256(revisionMaterial),
                    group.display, group.transcript, group.ownedGroup));
        });
        gaps.sort(Comparator.comparing(CatalogGap::canonicalRef).thenComparing(CatalogGap::reasonCode));
        return new CatalogResult(exercises, gaps);
    }

    private ComprehensionQuestionSpec toQuestion(AssessmentQuestion row) {
        if (!"MULTIPLE_CHOICE".equals(normalizeToken(row.getQuestionType()))) return null;
        String prompt = trimToNull(row.getQuestionText());
        if (prompt == null) return null;
        List<String> options = new ArrayList<>();
        boolean sawBlankOption = false;
        for (String raw : List.of(nullSafe(row.getOption1()), nullSafe(row.getOption2()),
                nullSafe(row.getOption3()), nullSafe(row.getOption4()))) {
            String option = trimToNull(raw);
            if (option == null) {
                sawBlankOption = true;
            } else {
                // A hole before another option changes its 1-based position and can
                // silently turn a correct_option into a different answer.
                if (sawBlankOption) return null;
                options.add(option);
            }
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

    /** Same text as {@link #sanitizePassage} but with paragraph and line breaks kept for display only. It never feeds
     * the fingerprint, so existing exercises and their revisions are unchanged. */
    private String displayPassage(String html) {
        String value = trimToNull(html);
        if (value == null) return null;
        value = HtmlUtils.htmlUnescape(value);
        value = value.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ")
                .replaceAll("(?i)<br\\s*/?>|</p\\s*>", "\n")
                .replaceAll("(?s)<[^>]+>", " ");
        StringBuilder out = new StringBuilder();
        for (String line : value.split("\n")) {
            String text = trimToNull(normalizeSpace(line));
            if (text != null) out.append(out.length() == 0 ? "" : "\n").append(text);
        }
        return out.length() == 0 ? null : out.toString();
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

    private String ownedLearningGroup(String sourceCode, String skill, String level) {
        if (sourceCode == null) return null;
        if ("N5".equals(level)) {
            String suffix = "READING".equals(skill) ? "-READ-" : "-LISTEN-";
            if (sourceCode.matches("LP-OWNED-SEKAI-N5-L\\d{2}" + suffix + "c\\d+")) return sourceCode;
        }
        if ("READING".equals(skill) && ("N5".equals(level) || "N4".equals(level))) {
            var matcher = java.util.regex.Pattern.compile("^(LP-OWNED-MINNA-" + level
                    + "-L\\d{2}-READ-P\\d{3})-Q\\d{3}$").matcher(sourceCode);
            if (matcher.matches()) return matcher.group(1);
        }
        if ("LISTENING".equals(skill) && ("N5".equals(level) || "N4".equals(level))) {
            var matcher = java.util.regex.Pattern.compile("^(LP-OWNED-MINNA-" + level
                    + "-L\\d{2}-LISTEN-c\\d+)-[LW]-c\\d+-[A-Za-z0-9-]+$").matcher(sourceCode);
            if (matcher.matches()) return matcher.group(1);
        }
        // OD-44 follow-up (owner 2026-10-20): one Speed Master N3 passage or clip per chapter slot.
        if ("N3".equals(level)) {
            var matcher = java.util.regex.Pattern.compile("^(LP-OWNED-SPEED-N3-C\\d{2}-"
                    + ("READING".equals(skill) ? "READ" : "LISTEN") + "-P[12])-Q\\d{3}$").matcher(sourceCode);
            if (matcher.matches()) return matcher.group(1);
        }
        return null;
    }

    /** Playback may use verified local media; the remote canonical URL stays in the fingerprint so
     * in-progress learner activity revisions remain valid when local media is provisioned later. */
    private String minnaPlaybackSource(AssessmentQuestion row, String level, String canonicalSource,
            String ownedSourceGroup) {
        if (ownedSourceGroup == null || !ownedSourceGroup.startsWith("LP-OWNED-MINNA-")) return canonicalSource;
        String context = row.getContextJson();
        if (context == null || context.isBlank()) return canonicalSource;
        try {
            JsonNode node = CONTEXT_MAPPER.readTree(context);
            JsonNode file = node.path("audio_file");
            if (file.isMissingNode() || file.isNull()) return canonicalSource;
            String relative = file.asText();
            var lesson = java.util.regex.Pattern.compile("-L(\\d{2})-LISTEN-").matcher(ownedSourceGroup);
            if (!lesson.find()) return null;
            String prefix = "audio/vnjpclub/" + level + "/"
                    + ("N5".equals(level) ? "minna-so-cap-1" : "minna-so-cap-2")
                    + "/bai-" + lesson.group(1) + "/";
            if (!relative.startsWith(prefix)
                    || !relative.substring(prefix.length()).matches("[A-Za-z0-9_-]+\\.mp3")
                    || !relative.substring(prefix.length()).equals(
                        URI.create(canonicalSource).getPath().replaceAll("^.*/", ""))) return null;
            return "/uploads/content-audio/minna/" + relative.substring("audio/".length());
        } catch (Exception invalidContext) {
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

    private record Group(String fingerprint, String source, String display, String transcript, String outcome, String topic,
            String ownedGroup, List<ComprehensionQuestionSpec> questions) { }
}
