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

    List<DialogueLineChunk> findByContentItemIdOrderByOrderIndexAsc(Long contentItemId);

    /**
     * Xoá sạch cụm của một lời trước khi ghi lại bộ mới. Ghi đè cả bộ thay vì sửa từng cụm vì bất biến
     * "ghép cụm ra đúng nguyên câu" chỉ kiểm được trên TOÀN BỘ danh sách, không kiểm được từng phần.
     */
    void deleteByContentItemId(Long contentItemId);
}
