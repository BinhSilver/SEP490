package com.jlas.shadowing.service;

import com.jlas.shadowing.model.Subtitle;
import com.jlas.shadowing.model.VocabularyEntry;
import com.jlas.shadowing.model.practice.ShadowingClozeCandidate;
import com.jlas.shadowing.model.practice.ShadowingExerciseAnalysis;
import com.jlas.shadowing.repository.ShadowingExerciseRepository;
import com.jlas.shadowing.repository.VocabularyEntryRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ShadowingExerciseAnalysisService {
    public static final String PROMPT_VERSION = "cloze-v1";
    private static final int AI_BATCH_SIZE = 8;
    private static final Set<String> ALLOWED_TYPES = Set.of(
            "VOCABULARY", "GRAMMAR", "VERB_FORM", "COUNTER", "EXPRESSION", "PROPER_NOUN", "SHORT_PHRASE");
    private static final Pattern ANNOTATED_KANJI = Pattern.compile("([\\p{IsHan}々〆ヵヶ]+)[（(]([^）)]+)[）)]");
    private static final Pattern JAPANESE_RUN = Pattern.compile("[\\p{IsHan}々ぁ-んァ-ヶー]{2,12}");
    private static final List<String> GRAMMAR_MARKERS = List.of(
            "けれど", "ながら", "という", "わけ", "はず", "ために", "のに", "ので", "なら", "ても", "たり", "そうです");

    private final ShadowingExerciseRepository repository;
    private final VocabularyEntryRepository vocabularyRepository;
    private final ShadowingExerciseAiClient aiClient;
    private final int maxAiSubtitles;

    public ShadowingExerciseAnalysisService(
            ShadowingExerciseRepository repository,
            VocabularyEntryRepository vocabularyRepository,
            ShadowingExerciseAiClient aiClient,
            @Value("${shadowing.practice.ai.max-subtitles-per-session:120}") int maxAiSubtitles) {
        this.repository = repository;
        this.vocabularyRepository = vocabularyRepository;
        this.aiClient = aiClient;
        this.maxAiSubtitles = Math.max(0, maxAiSubtitles);
    }

    public Map<Long, ShadowingExerciseAnalysis> ensureAnalyses(
            List<Subtitle> subtitles, int requestedCount, boolean allQuestions) {
        List<Subtitle> eligible = subtitles.stream()
                .filter(subtitle -> subtitle.getText() != null && !subtitle.getText().isBlank())
                .toList();
        Map<Long, ShadowingExerciseAnalysis> existing = new LinkedHashMap<>(
                repository.findBySubtitleIds(eligible.stream().map(Subtitle::getId).toList()));
        List<Subtitle> needsAi = new ArrayList<>();
        for (Subtitle subtitle : eligible) {
            String hash = analysisHash(subtitle);
            ShadowingExerciseAnalysis analysis = existing.get(subtitle.getId());
            if (analysis == null || !hash.equals(analysis.getSourceHash())
                    || !PROMPT_VERSION.equals(analysis.getPromptVersion())) {
                analysis = fallbackAnalysis(subtitle, hash);
                repository.save(analysis);
                existing.put(subtitle.getId(), analysis);
                needsAi.add(subtitle);
            } else if ("FALLBACK".equals(analysis.getAnalysisSource())) {
                needsAi.add(subtitle);
            }
        }
        if (aiClient.isAvailable() && !needsAi.isEmpty() && maxAiSubtitles > 0) {
            int desired = allQuestions ? needsAi.size() : Math.max(12, requestedCount * 2);
            List<Subtitle> selected = spreadByDifficulty(needsAi, existing,
                    Math.min(Math.min(desired, maxAiSubtitles), needsAi.size()));
            enrichWithAi(selected, existing);
        }
        return repository.findBySubtitleIds(eligible.stream().map(Subtitle::getId).toList());
    }

    /** Read-only path for starting a session. AI work belongs to the preparation job. */
    public Map<Long, ShadowingExerciseAnalysis> cachedAnalyses(List<Subtitle> subtitles) {
        Map<Long, Subtitle> byId = new HashMap<>();
        subtitles.forEach(subtitle -> byId.put(subtitle.getId(), subtitle));
        Map<Long, ShadowingExerciseAnalysis> cached = repository.findBySubtitleIds(
                subtitles.stream().map(Subtitle::getId).toList());
        cached.entrySet().removeIf(entry -> {
            Subtitle subtitle = byId.get(entry.getKey());
            return subtitle == null || !analysisHash(subtitle).equals(entry.getValue().getSourceHash())
                    || !PROMPT_VERSION.equals(entry.getValue().getPromptVersion());
        });
        return cached;
    }

    private void enrichWithAi(List<Subtitle> selected, Map<Long, ShadowingExerciseAnalysis> analyses) {
        for (int offset = 0; offset < selected.size(); offset += AI_BATCH_SIZE) {
            List<Subtitle> batch = selected.subList(offset, Math.min(selected.size(), offset + AI_BATCH_SIZE));
            List<ShadowingExerciseAiClient.AiAnalysis> generated = aiClient.analyze(batch);
            if (generated.size() != batch.size()) continue;
            Map<Long, Subtitle> subtitlesById = new HashMap<>();
            batch.forEach(subtitle -> subtitlesById.put(subtitle.getId(), subtitle));
            for (ShadowingExerciseAiClient.AiAnalysis ai : generated) {
                Subtitle subtitle = subtitlesById.get(ai.subtitleId());
                if (subtitle == null) continue;
                ShadowingExerciseAnalysis analysis = fromAi(subtitle, ai);
                if (analysis.getCandidates().isEmpty()) {
                    ShadowingExerciseAnalysis fallback = analyses.get(subtitle.getId());
                    if (fallback != null) analysis.setCandidates(fallback.getCandidates());
                }
                repository.save(analysis);
                analyses.put(subtitle.getId(), analysis);
            }
        }
    }

    private ShadowingExerciseAnalysis fromAi(Subtitle subtitle, ShadowingExerciseAiClient.AiAnalysis ai) {
        ShadowingExerciseAnalysis analysis = baseAnalysis(subtitle, analysisHash(subtitle));
        analysis.setVocabularyFactor(clampFactor(ai.vocabulary()));
        analysis.setGrammarFactor(clampFactor(ai.grammar()));
        analysis.setListeningSpeedFactor(clampFactor(ai.listeningSpeed()));
        analysis.setScriptComplexityFactor(clampFactor(ai.scriptComplexity()));
        analysis.setListeningTrapsFactor(clampFactor(ai.listeningTraps()));
        analysis.setSentenceDifficulty(sentenceDifficulty(analysis));
        analysis.setAnalysisSource("AI");
        List<ShadowingClozeCandidate> candidates = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ShadowingExerciseAiClient.AiCandidate aiCandidate : ai.candidates()) {
            if (candidates.size() >= 3) break;
            ShadowingClozeCandidate candidate = validatedCandidate(subtitle.getText(), aiCandidate);
            if (candidate != null && seen.add(candidate.getAnswerText() + "#" + candidate.getOccurrenceIndex())) {
                candidates.add(candidate);
            }
        }
        analysis.setCandidates(candidates);
        return analysis;
    }

    private ShadowingClozeCandidate validatedCandidate(
            String sentence, ShadowingExerciseAiClient.AiCandidate ai) {
        String answer = ai.answer() == null ? "" : ai.answer().strip();
        if (answer.isBlank() || !ALLOWED_TYPES.contains(ai.candidateType()) || ai.confidence() < 2) return null;
        int sentenceLength = sentence.codePointCount(0, sentence.length());
        int answerLength = answer.codePointCount(0, answer.length());
        if (answerLength > maxClozeLength(sentenceLength)) return null;
        int occurrences = occurrenceCount(sentence, answer);
        if (occurrences < ai.occurrence()) return null;
        if (answer.codePoints().allMatch(ShadowingExerciseAnalysisService::isPunctuationOrSpace)) return null;
        ShadowingClozeCandidate candidate = new ShadowingClozeCandidate();
        candidate.setAnswerText(answer);
        candidate.setReadingText(cleanReading(ai.reading()));
        candidate.setOccurrenceIndex(ai.occurrence());
        candidate.setCandidateType(ai.candidateType());
        candidate.setJlptLevel(normalizeJlpt(ai.jlptLevel()));
        candidate.setImportanceFactor(clampFactor(ai.importance()));
        candidate.setGrammarComplexityFactor(clampFactor(ai.grammarComplexity()));
        candidate.setContextPredictabilityFactor(clampFactor(ai.contextPredictability()));
        candidate.setAcousticConfusabilityFactor(clampFactor(ai.acousticConfusability()));
        candidate.setConfidenceFactor(clampFactor(ai.confidence()));
        candidate.setDifficultyScore(candidateDifficulty(candidate));
        candidate.setGenerationSource("AI");
        return candidate;
    }

    private ShadowingExerciseAnalysis fallbackAnalysis(Subtitle subtitle, String sourceHash) {
        ShadowingExerciseAnalysis analysis = baseAnalysis(subtitle, sourceHash);
        List<VocabularyEntry> vocabulary = vocabularyRepository.findContainedIn(subtitle.getText());
        int vocabularyFactor = vocabulary.stream().map(VocabularyEntry::getJlptLevel)
                .mapToInt(ShadowingExerciseAnalysisService::jlptFactor).max().orElse(1);
        analysis.setVocabularyFactor(vocabularyFactor);
        analysis.setGrammarFactor(grammarFactor(subtitle.getText()));
        analysis.setListeningSpeedFactor(speedFactor(subtitle));
        analysis.setScriptComplexityFactor(scriptFactor(subtitle.getText()));
        analysis.setListeningTrapsFactor(trapFactor(subtitle.getText()));
        analysis.setSentenceDifficulty(sentenceDifficulty(analysis));
        analysis.setAnalysisSource("FALLBACK");
        analysis.setCandidates(fallbackCandidates(subtitle, vocabulary));
        return analysis;
    }

    private ShadowingExerciseAnalysis baseAnalysis(Subtitle subtitle, String sourceHash) {
        ShadowingExerciseAnalysis analysis = new ShadowingExerciseAnalysis();
        analysis.setSubtitleId(subtitle.getId());
        analysis.setSourceHash(sourceHash);
        analysis.setPromptVersion(PROMPT_VERSION);
        analysis.setAnalyzedAt(LocalDateTime.now());
        return analysis;
    }

    private List<ShadowingClozeCandidate> fallbackCandidates(Subtitle subtitle, List<VocabularyEntry> vocabulary) {
        List<ShadowingClozeCandidate> result = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (VocabularyEntry entry : vocabulary) {
            if (result.size() >= 3) break;
            String answer = entry.getExpression();
            if (!validFallbackSpan(subtitle.getText(), answer) || !used.add(answer)) continue;
            ShadowingClozeCandidate candidate = new ShadowingClozeCandidate();
            candidate.setAnswerText(answer);
            candidate.setReadingText(entry.getReading());
            candidate.setOccurrenceIndex(1);
            candidate.setCandidateType("VOCABULARY");
            candidate.setJlptLevel(normalizeJlpt(entry.getJlptLevel()));
            candidate.setImportanceFactor(3);
            candidate.setGrammarComplexityFactor(0);
            candidate.setContextPredictabilityFactor(2);
            candidate.setAcousticConfusabilityFactor(1);
            candidate.setConfidenceFactor(4);
            candidate.setDifficultyScore(candidateDifficulty(candidate));
            candidate.setGenerationSource("DICTIONARY");
            result.add(candidate);
        }
        if (!result.isEmpty()) return result;
        Matcher annotated = ANNOTATED_KANJI.matcher(subtitle.getFurigana() == null ? "" : subtitle.getFurigana());
        while (annotated.find() && result.size() < 3) {
            addFallbackCandidate(result, used, subtitle.getText(), annotated.group(1), annotated.group(2), "VOCABULARY");
        }
        if (!result.isEmpty()) return result;
        Matcher run = JAPANESE_RUN.matcher(subtitle.getText());
        while (run.find() && result.size() < 3) {
            String value = run.group();
            if (value.codePointCount(0, value.length()) > 4) {
                int midpoint = value.offsetByCodePoints(0, value.codePointCount(0, value.length()) / 2);
                int end = value.offsetByCodePoints(midpoint, Math.min(3, value.codePointCount(midpoint, value.length())));
                value = value.substring(midpoint, end);
            }
            addFallbackCandidate(result, used, subtitle.getText(), value, value, "SHORT_PHRASE");
        }
        return result;
    }

    private void addFallbackCandidate(List<ShadowingClozeCandidate> result, Set<String> used,
                                      String sentence, String answer, String reading, String type) {
        if (!validFallbackSpan(sentence, answer) || !used.add(answer)) return;
        ShadowingClozeCandidate candidate = new ShadowingClozeCandidate();
        candidate.setAnswerText(answer);
        candidate.setReadingText(cleanReading(reading));
        candidate.setOccurrenceIndex(1);
        candidate.setCandidateType(type);
        candidate.setImportanceFactor(2);
        candidate.setContextPredictabilityFactor(2);
        candidate.setAcousticConfusabilityFactor(1);
        candidate.setConfidenceFactor(3);
        candidate.setDifficultyScore(candidateDifficulty(candidate));
        candidate.setGenerationSource("FALLBACK");
        result.add(candidate);
    }

    private static boolean validFallbackSpan(String sentence, String answer) {
        if (answer == null || answer.isBlank() || !sentence.contains(answer)) return false;
        int sentenceLength = sentence.codePointCount(0, sentence.length());
        int answerLength = answer.codePointCount(0, answer.length());
        return answerLength <= maxClozeLength(sentenceLength);
    }

    private static int maxClozeLength(int sentenceLength) {
        return Math.max(1, (int) Math.ceil(sentenceLength * 0.4));
    }

    private static List<Subtitle> spreadByDifficulty(List<Subtitle> subtitles,
            Map<Long, ShadowingExerciseAnalysis> analyses, int limit) {
        if (limit >= subtitles.size()) return new ArrayList<>(subtitles);
        List<Subtitle> sorted = new ArrayList<>(subtitles);
        sorted.sort(Comparator.comparingInt(subtitle -> analyses.get(subtitle.getId()).getSentenceDifficulty()));
        List<Subtitle> selected = new ArrayList<>();
        for (int i = 0; i < limit; i++) {
            int index = limit == 1 ? sorted.size() / 2
                    : (int) Math.round(i * (sorted.size() - 1.0) / (limit - 1.0));
            selected.add(sorted.get(index));
        }
        return selected;
    }

    private static int sentenceDifficulty(ShadowingExerciseAnalysis analysis) {
        double value = analysis.getVocabularyFactor() * 6.25
                + analysis.getGrammarFactor() * 5.0
                + analysis.getListeningSpeedFactor() * 5.0
                + analysis.getScriptComplexityFactor() * 3.75
                + analysis.getListeningTrapsFactor() * 5.0;
        return clampScore((int) Math.round(value));
    }

    private static int candidateDifficulty(ShadowingClozeCandidate candidate) {
        int lexical = switch (normalizeJlpt(candidate.getJlptLevel())) {
            case "N5" -> 4; case "N4" -> 10; case "N3" -> 17; case "N2" -> 24; case "N1" -> 30;
            default -> 14;
        };
        String reading = candidate.getReadingText();
        int moraLength = Math.min(15, Math.max(0, moraCount(reading == null || reading.isBlank()
                ? candidate.getAnswerText() : reading) - 1) * 3);
        int grammar = candidate.getGrammarComplexityFactor() * 15 / 4;
        int unpredictability = (4 - candidate.getContextPredictabilityFactor()) * 15 / 4;
        int acoustic = candidate.getAcousticConfusabilityFactor() * 15 / 4;
        int phrase = Set.of("GRAMMAR", "EXPRESSION", "SHORT_PHRASE", "VERB_FORM")
                .contains(candidate.getCandidateType()) ? 10 : 4;
        return clampScore(lexical + moraLength + grammar + unpredictability + acoustic + phrase);
    }

    private static int grammarFactor(String text) {
        int matches = (int) GRAMMAR_MARKERS.stream().filter(text::contains).count();
        return Math.min(4, matches + (text.length() > 35 ? 1 : 0));
    }

    private static int speedFactor(Subtitle subtitle) {
        double duration = Math.max(0.5, subtitle.getEndTime() - subtitle.getStartTime());
        double rate = moraCount(readingSentence(subtitle)) / duration;
        if (rate < 3.5) return 0;
        if (rate < 5.0) return 1;
        if (rate < 6.5) return 2;
        if (rate < 8.0) return 3;
        return 4;
    }

    private static int scriptFactor(String text) {
        long significant = text.codePoints().filter(cp -> !isPunctuationOrSpace(cp)).count();
        if (significant == 0) return 0;
        long complex = text.codePoints().filter(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN
                || Character.isDigit(cp)).count();
        double ratio = complex / (double) significant;
        if (ratio < .1) return 0;
        if (ratio < .25) return 1;
        if (ratio < .4) return 2;
        if (ratio < .6) return 3;
        return 4;
    }

    private static int trapFactor(String text) {
        int factor = 0;
        if (text.matches(".*[っッー].*")) factor++;
        if (text.matches(".*[0-9０-９一二三四五六七八九十百千万].*")) factor++;
        if (text.contains("ん") || text.contains("ン")) factor++;
        if (text.matches(".*(ちゃ|ちゅ|ちょ|しゃ|しゅ|しょ|じゃ|じゅ|じょ).*")) factor++;
        return Math.min(4, factor);
    }

    private static int jlptFactor(String level) {
        return switch (normalizeJlpt(level)) {
            case "N5" -> 0; case "N4" -> 1; case "N3" -> 2; case "N2" -> 3; case "N1" -> 4;
            default -> 1;
        };
    }

    private static String readingSentence(Subtitle subtitle) {
        String source = subtitle.getFurigana() == null ? subtitle.getText() : subtitle.getFurigana();
        return ANNOTATED_KANJI.matcher(source).replaceAll("$2");
    }

    private static int moraCount(String text) {
        if (text == null || text.isBlank()) return 0;
        int count = 0;
        for (int cp : text.codePoints().toArray()) {
            if (isPunctuationOrSpace(cp)) continue;
            if ("ゃゅょぁぃぅぇぉゎャュョァィゥェォヮ".indexOf(cp) >= 0) continue;
            count++;
        }
        return count;
    }

    private static int occurrenceCount(String text, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = text.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }

    private static boolean isPunctuationOrSpace(int cp) {
        int type = Character.getType(cp);
        return Character.isWhitespace(cp) || type == Character.CONNECTOR_PUNCTUATION
                || type == Character.DASH_PUNCTUATION || type == Character.START_PUNCTUATION
                || type == Character.END_PUNCTUATION || type == Character.INITIAL_QUOTE_PUNCTUATION
                || type == Character.FINAL_QUOTE_PUNCTUATION || type == Character.OTHER_PUNCTUATION;
    }

    private static String cleanReading(String reading) {
        if (reading == null) return "";
        String value = reading.strip();
        return value.matches("[ぁ-んァ-ヶー]+") ? value : "";
    }

    private static String normalizeJlpt(String level) {
        if (level == null) return "";
        String normalized = level.strip().toUpperCase();
        return Set.of("N1", "N2", "N3", "N4", "N5").contains(normalized) ? normalized : "";
    }

    private static int clampFactor(int value) {
        return Math.max(0, Math.min(4, value));
    }

    private static int clampScore(int value) {
        return Math.max(0, Math.min(100, value));
    }

    private static String analysisHash(Subtitle subtitle) {
        String source = String.join("\u001f",
                subtitle.getText() == null ? "" : subtitle.getText(),
                subtitle.getFurigana() == null ? "" : subtitle.getFurigana(),
                Double.toString(subtitle.getStartTime()),
                Double.toString(subtitle.getEndTime()));
        return hash(source);
    }

    private static String hash(String text) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(bytes);
        } catch (Exception exception) {
            throw new IllegalStateException("Không tạo được hash phụ đề", exception);
        }
    }
}
