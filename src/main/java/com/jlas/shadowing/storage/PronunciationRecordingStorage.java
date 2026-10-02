package com.jlas.shadowing.storage;

import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

public interface PronunciationRecordingStorage {
    StoredRecording store(Long userId, Long videoId, Long subtitleId, MultipartFile audio);
    Resource load(String storageKey);
    void delete(String storageKey);

    record StoredRecording(String storageKey, String contentType, long sizeBytes) {}
}
