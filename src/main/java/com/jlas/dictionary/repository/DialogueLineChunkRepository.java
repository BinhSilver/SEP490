package com.jlas.dictionary.repository;

import com.jlas.dictionary.entity.DialogueLineChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

/** Repository cho {@link DialogueLineChunk}. */
@Repository
public interface DialogueLineChunkRepository extends JpaRepository<DialogueLineChunk, Long> {

    /** Cụm tách nghĩa của nhiều lời thoại, 1 query; caller gộp theo content_item_id. */
    List<DialogueLineChunk> findByContentItemIdInOrderByContentItemIdAscOrderIndexAsc(Collection<Long> contentItemIds);
}
