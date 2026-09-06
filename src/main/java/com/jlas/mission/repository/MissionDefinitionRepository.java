package com.jlas.mission.repository;

import com.jlas.mission.entity.MissionDefinition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Repository cho {@link MissionDefinition}. */
@Repository
public interface MissionDefinitionRepository extends JpaRepository<MissionDefinition, Long> { }
