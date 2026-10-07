package com.jlas.dictionary.repository;

import com.jlas.dictionary.entity.ContentVoice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Repository cho {@link ContentVoice}. Khoá chính là mã giọng (code), không phải số. */
@Repository
public interface ContentVoiceRepository extends JpaRepository<ContentVoice, String> {
}
