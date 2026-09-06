package com.jlas.kaiwa.repository;

import com.jlas.kaiwa.entity.Scenario;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Repository cho {@link Scenario}. */
@Repository
public interface ScenarioRepository extends JpaRepository<Scenario, Long> { }
