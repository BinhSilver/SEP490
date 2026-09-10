package com.jlas.shadowing.repository;

import com.jlas.shadowing.model.Subtitle;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Repository
public interface SubtitleRepository extends JpaRepository<Subtitle, Long> {
    List<Subtitle> findByVideoIdOrderByStartTimeAsc(Long videoId);
    List<Subtitle> findByVideoIdOrderBySortOrderAscStartTimeAsc(Long videoId);

    @Modifying
    @Transactional
    @Query("DELETE FROM Subtitle s WHERE s.video.id = :videoId")
    void deleteByVideoId(Long videoId);
}
