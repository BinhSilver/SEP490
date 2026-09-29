package com.jlas.kaiwa.service;

import com.jlas.kaiwa.model.*;
import com.jlas.kaiwa.repository.*;
import com.jlas.kaiwa.service.scenario.ScenarioContentService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CatalogService {

    private final CategoryRepository categoryRepository;
    private final ScenarioRepository scenarioRepository;
    private final ScenarioContentService scenarioContent;

    public CatalogService(
            CategoryRepository categoryRepository,
            ScenarioRepository scenarioRepository,
            ScenarioContentService scenarioContent) {
        this.categoryRepository = categoryRepository;
        this.scenarioRepository = scenarioRepository;
        this.scenarioContent = scenarioContent;
    }

    @Transactional(readOnly = true)
    public List<Category> listCategories() {
        return categoryRepository.findByActiveTrueOrderByDisplayOrderAsc();
    }

    @Transactional(readOnly = true)
    public Category getCategory(Long id) {
        return categoryRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Category not found"));
    }

    @Transactional(readOnly = true)
    public List<Scenario> listScenarios(Long categoryId) {
        return scenarioRepository.findByCategoryIdAndActiveTrueOrderByIdAsc(categoryId);
    }

    @Transactional(readOnly = true)
    public long countScenarios(Long categoryId) {
        return scenarioRepository.countByCategoryIdAndActiveTrue(categoryId);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> scenarioDetail(Long id) {
        Scenario s = scenarioRepository.findActiveDetail(id)
                .orElseThrow(() -> new IllegalArgumentException("Scenario not found"));
        Map<String, Object> map = new HashMap<>();
        map.put("scenario", s);
        map.put("context", scenarioContent.context(s));
        map.put("missions", scenarioContent.missions(s));
        map.put("objectives", scenarioContent.objectives(s));
        map.put("states", scenarioContent.states(s));
        map.put("hints", scenarioContent.hints(s));
        map.put("endings", scenarioContent.endings(s));
        map.put("vocabulary", scenarioContent.vocabulary(s));
        map.put("grammar", scenarioContent.grammar(s));
        map.put("dialogues", scenarioContent.dialogues(s));
        return map;
    }

    @Transactional(readOnly = true)
    public Scenario getScenario(Long id) {
        return scenarioRepository.findActiveDetail(id)
                .orElseThrow(() -> new IllegalArgumentException("Scenario not found"));
    }
}
