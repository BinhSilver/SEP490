package com.jlas.shadowing.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Table(name = "dictionary_cache")
@Data
public class DictionaryCache {
    @Id
    @Column(length = 100)
    private String word;

    @Column(columnDefinition = "TEXT")
    private String data;

    private LocalDateTime cachedAt;

    @PrePersist
    protected void onCreate() {
        cachedAt = LocalDateTime.now();
    }
}
