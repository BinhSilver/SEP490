package com.jlas.kaiwa.service;

import com.jlas.kaiwa.model.*;
import com.jlas.kaiwa.repository.*;
import com.jlas.kaiwa.service.scenario.ScenarioContentService;
import com.jlas.kaiwa.util.ScenarioIcons;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
        return scenarioRepository.countByCategoryIdAndActiveTrueAndParentScenarioIsNull(categoryId);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> categoryListing(Long categoryId) {
        var roots = listScenarios(categoryId);
        var icons = new LinkedHashMap<Long, String>();
        roots.forEach(s -> icons.put(s.getId(), ScenarioIcons.topic(s)));
        return Map.of("category", getCategory(categoryId), "scenarios", roots, "scenarioIcons", icons);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> categoryTree(Long categoryId) {
        List<Scenario> roots = listScenarios(categoryId);
        Map<Long, List<Scenario>> casesByScenario = new LinkedHashMap<>();
        Map<Long, List<ConversationState>> caseStates = new LinkedHashMap<>();
        Map<Long, ScenarioCompactDefinition> graphs = new LinkedHashMap<>();
        Map<Long, String> scenarioIcons = new LinkedHashMap<>();
        Map<Long, String> caseIcons = new LinkedHashMap<>();
        int caseCount = 0;
        for (Scenario root : roots) {
            if (scenarioContent.isGraph(root)) graphs.put(root.getId(), scenarioContent.flow(root).orElseThrow());
            scenarioIcons.put(root.getId(), ScenarioIcons.topic(root));
            List<Scenario> cases = scenarioRepository.findActiveCases(root.getId());
            casesByScenario.put(root.getId(), cases);
            caseCount += cases.size();
            for (Scenario practiceCase : cases) {
                List<ConversationState> states = scenarioContent.states(practiceCase);
                caseStates.put(practiceCase.getId(), states);
                caseIcons.put(practiceCase.getId(), practiceCase.getParentScenario() == null || states.isEmpty()
                        ? ScenarioIcons.topic(practiceCase) : ScenarioIcons.step(practiceCase, states.getFirst().getCode()));
            }
        }
        Map<String, Object> model = new HashMap<>();
        model.put("category", getCategory(categoryId));
        model.put("scenarios", roots);
        model.put("practiceCasesByScenario", casesByScenario);
        model.put("caseStates", caseStates);
        model.put("practiceCaseCount", caseCount);
        model.put("scenarioIcons", scenarioIcons);
        model.put("graphs", graphs);
        model.put("caseIcons", caseIcons);
        return model;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> scenarioDetail(Long id) {
        return scenarioDetail(id, null);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> scenarioDetail(Long id, String stageCode) {
        Scenario s = scenarioRepository.findActiveDetail(id)
                .orElseThrow(() -> new IllegalArgumentException("Scenario not found"));
        Map<String, Object> map = new HashMap<>();
        map.put("scenario", s);
        Long rootId = s.getParentScenario() == null ? s.getId() : s.getParentScenario().getId();
        map.put("practiceCases", scenarioRepository.findActiveCases(rootId));
        map.put("context", scenarioContent.context(s));
        map.put("missions", scenarioContent.missions(s));
        List<ScenarioObjective> objectives = scenarioContent.objectives(s);
        List<ConversationState> states = scenarioContent.states(s);
        Map<String, String> stateIcons = new LinkedHashMap<>();
        Map<String, String> objectiveIcons = new LinkedHashMap<>();
        states.forEach(state -> stateIcons.put(state.getCode(), ScenarioIcons.step(s, state.getCode())));
        objectives.forEach(objective -> objectiveIcons.put(objective.getCode(), ScenarioIcons.step(s, objective.getCode())));
        boolean graph = scenarioContent.isGraph(s);
        map.put("graph", graph ? scenarioContent.flow(s).orElseThrow() : null);
        map.put("objectives", graph ? objectives.stream().filter(ScenarioObjective::isRequired).toList() : objectives);
        map.put("states", states);
        map.put("scenarioIcon", ScenarioIcons.topic(s));
        map.put("stateIcons", stateIcons);
        map.put("objectiveIcons", objectiveIcons);
        map.put("hints", scenarioContent.hints(s));
        map.put("endings", scenarioContent.endings(s));
        map.put("vocabulary", scenarioContent.vocabulary(s));
        map.put("grammar", scenarioContent.grammar(s));
        map.put("dialogues", scenarioContent.dialogues(s));
        map.put("branchDialogues", scenarioContent.branchDialogues(s));
        map.put("scenarioSpeakers", scenarioContent.flow(s).map(com.jlas.kaiwa.model.ScenarioCompactDefinition::speakers).orElse(List.of()));
        map.put("lessonSkills", scenarioContent.flow(s).map(ScenarioCompactDefinition::skillTransfers).orElse(List.of()));
        if (stageCode != null) scenarioContent.flow(s).stream()
                .flatMap(f -> f.stages() == null ? java.util.stream.Stream.empty() : f.stages().stream())
                .filter(stage -> stageCode.equals(stage.code())).findFirst().ifPresent(stage -> {
                    map.put("selectedStage", stage.code());
                    map.put("selectedStageTitle", stage.title());
                    map.put("objectives", objectives.stream().filter(o -> stage.requiredGoalCodes().contains(o.getCode())).toList());
                    map.put("dialogues", List.of());
                    map.put("branchDialogues", scenarioContent.branchDialogues(s).stream()
                            .filter(d -> stage.code().equals(d.code())).toList());
                });
        return map;
    }

    @Transactional(readOnly = true)
    public Scenario getScenario(Long id) {
        return scenarioRepository.findActiveDetail(id)
                .orElseThrow(() -> new IllegalArgumentException("Scenario not found"));
    }
}
