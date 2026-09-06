package com.jlas.shadowing.repository;

import com.jlas.shadowing.entity.Video;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

/** Repository cho {@link Video}. */
@Repository
public interface VideoRepository extends JpaRepository<Video, Long> {
    List<Video> findByOwnerId(Long ownerId);
}
