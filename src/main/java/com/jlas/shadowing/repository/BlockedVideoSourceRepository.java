package com.jlas.shadowing.repository;

import com.jlas.shadowing.model.BlockedVideoSource;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BlockedVideoSourceRepository extends JpaRepository<BlockedVideoSource, String> {
}
