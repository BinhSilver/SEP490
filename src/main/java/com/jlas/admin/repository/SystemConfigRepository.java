package com.jlas.admin.repository;

import com.jlas.admin.entity.SystemConfig;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SystemConfigRepository extends JpaRepository<SystemConfig, String> {
    @EntityGraph(attributePaths = "updatedBy")
    List<SystemConfig> findAllBy();
}
