package com.jlas.kaiwa.repository;

import com.jlas.kaiwa.model.ScenarioMission;
import java.util.List;
import org.springframework.stereotype.Repository;

@Repository
public class ScenarioMissionRepository extends RuntimeScenarioStore<ScenarioMission> {
    public List<ScenarioMission> findByScenarioIdOrderByDisplayOrderAsc(Long id) { return byScenario(id); }
}
