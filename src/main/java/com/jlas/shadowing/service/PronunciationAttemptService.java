package com.jlas.shadowing.service;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.shadowing.dto.PronunciationAssessmentDto;
import com.jlas.shadowing.dto.PronunciationPhonemeDto;
import com.jlas.shadowing.dto.PronunciationWordDto;
import com.jlas.shadowing.model.PronunciationAttempt;
import com.jlas.shadowing.model.PronunciationPhonemeResult;
import com.jlas.shadowing.model.PronunciationWordResult;
import com.jlas.shadowing.model.Subtitle;
import com.jlas.shadowing.repository.PronunciationAttemptRepository;
import com.jlas.shadowing.storage.PronunciationRecordingStorage;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;

@Service
public class PronunciationAttemptService {
    private final PronunciationAttemptRepository repository;
    private final PronunciationRecordingStorage storage;

    public PronunciationAttemptService(PronunciationAttemptRepository repository,
                                       PronunciationRecordingStorage storage) {
        this.repository = repository;
        this.storage = storage;
    }

    @Transactional
    public PronunciationAssessmentDto save(Long userId, Subtitle subtitle, MultipartFile audio,
                                           double durationSeconds, PronunciationAssessmentDto assessment) {
        var stored = storage.store(userId, subtitle.getVideo().getId(), subtitle.getId(), audio);
        PronunciationAttempt attempt = toModel(userId, subtitle, stored, durationSeconds, assessment);
        try {
            repository.save(attempt);
        } catch (RuntimeException exception) {
            storage.delete(stored.storageKey());
            throw exception;
        }
        return toDto(attempt);
    }

    @Transactional(readOnly = true)
    public List<PronunciationAssessmentDto> latestForVideo(Long userId, Long videoId) {
        return repository.findLatestByVideo(userId, videoId).stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public List<PronunciationAssessmentDto> historyForVideo(Long userId, Long videoId) {
        return repository.findHistoryByVideo(userId, videoId).stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public StoredOwnedRecording loadOwnedAudio(Long userId, Long attemptId) {
        PronunciationAttempt attempt = owned(userId, attemptId);
        return new StoredOwnedRecording(storage.load(attempt.getStorageKey()), attempt.getAudioContentType());
    }

    @Transactional
    public void delete(Long userId, Long attemptId) {
        PronunciationAttempt attempt = owned(userId, attemptId);
        repository.deleteOwned(userId, attemptId);
        storage.delete(attempt.getStorageKey());
    }

    private PronunciationAttempt owned(Long userId, Long attemptId) {
        PronunciationAttempt attempt = repository.findOwned(userId, attemptId);
        if (attempt == null) throw new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy bản thu");
        return attempt;
    }

    private static PronunciationAttempt toModel(Long userId, Subtitle subtitle,
                                                PronunciationRecordingStorage.StoredRecording stored,
                                                double durationSeconds, PronunciationAssessmentDto result) {
        PronunciationAttempt attempt = new PronunciationAttempt();
        attempt.setUserId(userId);
        attempt.setVideoId(subtitle.getVideo().getId());
        attempt.setSubtitleId(subtitle.getId());
        attempt.setStorageKey(stored.storageKey());
        attempt.setAudioContentType(stored.contentType());
        attempt.setAudioSizeBytes(stored.sizeBytes());
        attempt.setAudioDurationSeconds(durationSeconds);
        attempt.setRecognitionStatus(result.getRecognitionStatus());
        attempt.setReferenceText(result.getReferenceText());
        attempt.setRecognizedText(result.getRecognizedText());
        attempt.setPronunciationScore(result.getPronunciationScore());
        attempt.setAccuracyScore(result.getAccuracyScore());
        attempt.setFluencyScore(result.getFluencyScore());
        attempt.setCompletenessScore(result.getCompletenessScore());
        attempt.setProsodyScore(result.getProsodyScore());
        attempt.setFeedback(result.getFeedback());
        List<PronunciationWordResult> words = new ArrayList<>();
        for (int position = 0; position < result.getWords().size(); position++) {
            PronunciationWordDto source = result.getWords().get(position);
            PronunciationWordResult word = new PronunciationWordResult();
            word.setPosition(position);
            word.setWord(source.getWord());
            word.setAccuracyScore(source.getAccuracyScore());
            word.setErrorType(source.getErrorType());
            for (int phonemePosition = 0; phonemePosition < source.getPhonemes().size(); phonemePosition++) {
                PronunciationPhonemeDto sourcePhoneme = source.getPhonemes().get(phonemePosition);
                PronunciationPhonemeResult phoneme = new PronunciationPhonemeResult();
                phoneme.setPosition(phonemePosition);
                phoneme.setPhoneme(sourcePhoneme.getPhoneme());
                phoneme.setAccuracyScore(sourcePhoneme.getAccuracyScore());
                word.getPhonemes().add(phoneme);
            }
            words.add(word);
        }
        attempt.setWords(words);
        return attempt;
    }

    private PronunciationAssessmentDto toDto(PronunciationAttempt attempt) {
        PronunciationAssessmentDto result = new PronunciationAssessmentDto();
        result.setAttemptId(attempt.getId());
        result.setSubtitleId(attempt.getSubtitleId());
        result.setAudioUrl("/api/pronunciation/attempts/" + attempt.getId() + "/audio");
        result.setAudioSizeBytes(attempt.getAudioSizeBytes());
        result.setCreatedAt(attempt.getCreatedAt());
        result.setRecognitionStatus(attempt.getRecognitionStatus());
        result.setReferenceText(attempt.getReferenceText());
        result.setRecognizedText(attempt.getRecognizedText());
        result.setPronunciationScore(attempt.getPronunciationScore());
        result.setAccuracyScore(attempt.getAccuracyScore());
        result.setFluencyScore(attempt.getFluencyScore());
        result.setCompletenessScore(attempt.getCompletenessScore());
        result.setProsodyScore(attempt.getProsodyScore());
        result.setFeedback(attempt.getFeedback());
        for (PronunciationWordResult source : attempt.getWords()) {
            PronunciationWordDto word = new PronunciationWordDto();
            word.setWord(source.getWord());
            word.setAccuracyScore(source.getAccuracyScore());
            word.setErrorType(source.getErrorType());
            for (PronunciationPhonemeResult sourcePhoneme : source.getPhonemes()) {
                word.getPhonemes().add(new PronunciationPhonemeDto(
                        sourcePhoneme.getPhoneme(), sourcePhoneme.getAccuracyScore()));
            }
            result.getWords().add(word);
        }
        return result;
    }

    public record StoredOwnedRecording(Resource resource, String contentType) {}
}
