package com.jlas.dictionary.repository;

import com.jlas.dictionary.entity.ContentAudio;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

/** Repository cho {@link ContentAudio}. */
@Repository
public interface ContentAudioRepository extends JpaRepository<ContentAudio, Long> {

    /**
     * Audio của một tập owner cùng loại, kèm giọng (fetch join), 1 query.
     * owner_id là khoá đa hình: VOCABULARY_WORD trỏ vocabulary_entry.id, CONTENT_ITEM trỏ
     * content_item.id — hai tập id trùng số nhưng khác nghĩa, nên caller PHẢI gọi riêng
     * theo từng ownerType, không gộp chung một list id.
     */
    @Query("""
            select a from ContentAudio a
              join fetch a.voice
            where a.ownerType = :ownerType and a.ownerId in :ownerIds
            order by a.ownerId, a.voice.code
            """)
    List<ContentAudio> findByOwnerTypeAndOwnerIdIn(@Param("ownerType") String ownerType,
                                                   @Param("ownerIds") Collection<Long> ownerIds);
}
