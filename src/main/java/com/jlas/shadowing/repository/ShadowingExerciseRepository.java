package com.jlas.shadowing.repository;

import com.jlas.shadowing.model.practice.ShadowingClozeCandidate;
import com.jlas.shadowing.model.practice.ShadowingExerciseAnalysis;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
public class ShadowingExerciseRepository {
    private final JdbcTemplate jdbc;

    public ShadowingExerciseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<Long, ShadowingExerciseAnalysis> findBySubtitleIds(List<Long> subtitleIds) {
        if (subtitleIds == null || subtitleIds.isEmpty()) return Map.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(subtitleIds.size(), "?"));
        Map<Long, ShadowingExerciseAnalysis> result = new LinkedHashMap<>();
        jdbc.query("SELECT id, subtitle_id, source_hash, prompt_version, vocabulary_factor, grammar_factor, "
                        + "listening_speed_factor, script_complexity_factor, listening_traps_factor, "
                        + "sentence_difficulty, analysis_source, analyzed_at FROM shadowing_exercise_analysis "
                        + "WHERE subtitle_id IN (" + placeholders + ")",
                (rs, rowNum) -> {
                    ShadowingExerciseAnalysis analysis = new ShadowingExerciseAnalysis();
                    analysis.setId(rs.getLong("id"));
                    analysis.setSubtitleId(rs.getLong("subtitle_id"));
                    analysis.setSourceHash(rs.getString("source_hash"));
                    analysis.setPromptVersion(rs.getString("prompt_version"));
                    analysis.setVocabularyFactor(rs.getInt("vocabulary_factor"));
                    analysis.setGrammarFactor(rs.getInt("grammar_factor"));
                    analysis.setListeningSpeedFactor(rs.getInt("listening_speed_factor"));
                    analysis.setScriptComplexityFactor(rs.getInt("script_complexity_factor"));
                    analysis.setListeningTrapsFactor(rs.getInt("listening_traps_factor"));
                    analysis.setSentenceDifficulty(rs.getInt("sentence_difficulty"));
                    analysis.setAnalysisSource(rs.getString("analysis_source"));
                    Timestamp analyzed = rs.getTimestamp("analyzed_at");
                    analysis.setAnalyzedAt(analyzed == null ? null : analyzed.toLocalDateTime());
                    result.put(analysis.getSubtitleId(), analysis);
                    return analysis;
                }, subtitleIds.toArray());
        if (result.isEmpty()) return result;
        List<Long> analysisIds = result.values().stream().map(ShadowingExerciseAnalysis::getId).toList();
        Map<Long, ShadowingExerciseAnalysis> byId = new LinkedHashMap<>();
        result.values().forEach(analysis -> byId.put(analysis.getId(), analysis));
        String candidatePlaceholders = String.join(",", java.util.Collections.nCopies(analysisIds.size(), "?"));
        jdbc.query("SELECT id, analysis_id, candidate_order, answer_text, reading_text, occurrence_index, "
                        + "candidate_type, jlpt_level, importance_factor, grammar_complexity_factor, "
                        + "context_predictability_factor, acoustic_confusability_factor, confidence_factor, "
                        + "difficulty_score, generation_source FROM shadowing_cloze_candidate "
                        + "WHERE analysis_id IN (" + candidatePlaceholders + ") ORDER BY analysis_id, candidate_order",
                (rs, rowNum) -> {
                    ShadowingClozeCandidate candidate = new ShadowingClozeCandidate();
                    candidate.setId(rs.getLong("id"));
                    candidate.setAnalysisId(rs.getLong("analysis_id"));
                    candidate.setCandidateOrder(rs.getInt("candidate_order"));
                    candidate.setAnswerText(rs.getString("answer_text"));
                    candidate.setReadingText(rs.getString("reading_text"));
                    candidate.setOccurrenceIndex(rs.getInt("occurrence_index"));
                    candidate.setCandidateType(rs.getString("candidate_type"));
                    candidate.setJlptLevel(rs.getString("jlpt_level"));
                    candidate.setImportanceFactor(rs.getInt("importance_factor"));
                    candidate.setGrammarComplexityFactor(rs.getInt("grammar_complexity_factor"));
                    candidate.setContextPredictabilityFactor(rs.getInt("context_predictability_factor"));
                    candidate.setAcousticConfusabilityFactor(rs.getInt("acoustic_confusability_factor"));
                    candidate.setConfidenceFactor(rs.getInt("confidence_factor"));
                    candidate.setDifficultyScore(rs.getInt("difficulty_score"));
                    candidate.setGenerationSource(rs.getString("generation_source"));
                    ShadowingExerciseAnalysis parent = byId.get(candidate.getAnalysisId());
                    if (parent != null) parent.getCandidates().add(candidate);
                    return candidate;
                }, analysisIds.toArray());
        return result;
    }

    @Transactional
    public void save(ShadowingExerciseAnalysis analysis) {
        jdbc.update("INSERT INTO shadowing_exercise_analysis "
                        + "(subtitle_id, source_hash, prompt_version, vocabulary_factor, grammar_factor, "
                        + "listening_speed_factor, script_complexity_factor, listening_traps_factor, "
                        + "sentence_difficulty, analysis_source, analyzed_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE source_hash=VALUES(source_hash), prompt_version=VALUES(prompt_version), "
                        + "vocabulary_factor=VALUES(vocabulary_factor), grammar_factor=VALUES(grammar_factor), "
                        + "listening_speed_factor=VALUES(listening_speed_factor), script_complexity_factor=VALUES(script_complexity_factor), "
                        + "listening_traps_factor=VALUES(listening_traps_factor), sentence_difficulty=VALUES(sentence_difficulty), "
                        + "analysis_source=VALUES(analysis_source), analyzed_at=VALUES(analyzed_at)",
                analysis.getSubtitleId(), analysis.getSourceHash(), analysis.getPromptVersion(),
                analysis.getVocabularyFactor(), analysis.getGrammarFactor(), analysis.getListeningSpeedFactor(),
                analysis.getScriptComplexityFactor(), analysis.getListeningTrapsFactor(),
                analysis.getSentenceDifficulty(), analysis.getAnalysisSource(),
                Timestamp.valueOf(analysis.getAnalyzedAt() == null ? LocalDateTime.now() : analysis.getAnalyzedAt()));
        Long analysisId = jdbc.queryForObject(
                "SELECT id FROM shadowing_exercise_analysis WHERE subtitle_id = ?", Long.class, analysis.getSubtitleId());
        analysis.setId(analysisId);
        jdbc.update("DELETE FROM shadowing_cloze_candidate WHERE analysis_id = ?", analysisId);
        List<ShadowingClozeCandidate> candidates = analysis.getCandidates() == null
                ? List.of() : new ArrayList<>(analysis.getCandidates());
        for (int i = 0; i < candidates.size(); i++) {
            ShadowingClozeCandidate candidate = candidates.get(i);
            candidate.setAnalysisId(analysisId);
            candidate.setCandidateOrder(i);
            jdbc.update("INSERT INTO shadowing_cloze_candidate "
                            + "(analysis_id, candidate_order, answer_text, reading_text, occurrence_index, candidate_type, jlpt_level, "
                            + "importance_factor, grammar_complexity_factor, context_predictability_factor, "
                            + "acoustic_confusability_factor, confidence_factor, difficulty_score, generation_source) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    analysisId, i, candidate.getAnswerText(), candidate.getReadingText(), candidate.getOccurrenceIndex(),
                    candidate.getCandidateType(), candidate.getJlptLevel(), candidate.getImportanceFactor(),
                    candidate.getGrammarComplexityFactor(), candidate.getContextPredictabilityFactor(),
                    candidate.getAcousticConfusabilityFactor(), candidate.getConfidenceFactor(),
                    candidate.getDifficultyScore(), candidate.getGenerationSource());
        }
    }
}
