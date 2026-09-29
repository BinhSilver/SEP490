package com.jlas.kaiwa.service.scenario;

import com.jlas.kaiwa.model.*;
import com.jlas.kaiwa.repository.ScenarioRepository;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Materializes the compact JSON columns on {@link Scenario} into the legacy
 * view models still consumed by controllers and the conversation engine.
 * The compact columns are the canonical source in the merged schema.
 */
@Service
public class ScenarioContentService {

    private final ScenarioRepository scenarioRepository;
    private final ScenarioJsonMapper jsonMapper;

    public ScenarioContentService(ScenarioRepository scenarioRepository, ScenarioJsonMapper jsonMapper) {
        this.scenarioRepository = scenarioRepository;
        this.jsonMapper = jsonMapper;
    }

    @Transactional(readOnly = true)
    public Scenario scenario(Long scenarioId) {
        return scenarioRepository.findActiveDetail(scenarioId)
                .orElseThrow(() -> new IllegalArgumentException("Scenario not found"));
    }

    public ScenarioContext context(Scenario scenario) {
        ScenarioContext context = new ScenarioContext();
        context.setScenario(scenario);
        context.setBackground(scenario.getBackground());
        context.setLocation(scenario.getLocation());
        context.setTime(scenario.getTimeOfDay());
        context.setCultureNote(scenario.getCultureNote());
        return context;
    }

    public List<ScenarioMission> missions(Scenario scenario) {
        if (flow(scenario).map(ScenarioCompactDefinition::goals).orElse(List.of()).isEmpty()) {
            return List.of();
        }
        ScenarioMission mission = new ScenarioMission();
        mission.setId(syntheticId(scenario, 1));
        mission.setScenario(scenario);
        mission.setTitle(scenario.getTitle());
        mission.setDescription(scenario.getDescription());
        mission.setDisplayOrder(1);
        mission.setRequired(true);
        return List.of(mission);
    }

    public List<ScenarioObjective> objectives(Long scenarioId) {
        return objectives(scenario(scenarioId));
    }

    public List<ScenarioObjective> objectives(Scenario scenario) {
        List<ScenarioCompactDefinition.Goal> goals =
                flow(scenario).map(ScenarioCompactDefinition::goals).orElse(List.of());
        List<ScenarioObjective> result = new ArrayList<>();
        for (int i = 0; i < goals.size(); i++) {
            ScenarioCompactDefinition.Goal goal = goals.get(i);
            ScenarioObjective objective = new ScenarioObjective();
            objective.setId(syntheticId(scenario, i + 1));
            objective.setScenario(scenario);
            objective.setCode(goal.code());
            objective.setTitle(goal.title());
            objective.setDescription(goal.description());
            objective.setDisplayOrder(goal.displayOrder() != null ? goal.displayOrder() : i + 1);
            objective.setRequired(goal.required() == null || goal.required());
            result.add(objective);
        }
        return List.copyOf(result);
    }

    public List<ScenarioSuccessCondition> successConditions(Scenario scenario) {
        Map<String, ScenarioObjective> objectives = new LinkedHashMap<>();
        for (ScenarioObjective objective : objectives(scenario)) {
            objectives.put(objective.getCode(), objective);
        }
        List<ScenarioCompactDefinition.Goal> goals =
                flow(scenario).map(ScenarioCompactDefinition::goals).orElse(List.of());
        List<ScenarioSuccessCondition> result = new ArrayList<>();
        int order = 1;
        for (ScenarioCompactDefinition.Goal goal : goals) {
            if (goal.conditions() == null) continue;
            for (ScenarioCompactDefinition.Condition condition : goal.conditions()) {
                ScenarioSuccessCondition row = new ScenarioSuccessCondition();
                row.setId(syntheticId(scenario, order++));
                row.setScenario(scenario);
                row.setObjective(objectives.get(goal.code()));
                row.setCode(goal.code());
                row.setDescription(goal.description());
                row.setConditionType(condition.conditionType());
                row.setMatchValue(condition.matchValue());
                result.add(row);
            }
        }
        return List.copyOf(result);
    }

    public List<ConversationState> states(Long scenarioId) {
        return states(scenario(scenarioId));
    }

    public List<ConversationState> states(Scenario scenario) {
        List<ScenarioCompactDefinition.State> definitions =
                flow(scenario).map(ScenarioCompactDefinition::states).orElse(List.of());
        Map<String, ConversationState> byCode = new LinkedHashMap<>();
        for (int i = 0; i < definitions.size(); i++) {
            ScenarioCompactDefinition.State definition = definitions.get(i);
            ConversationState state = new ConversationState();
            state.setId(syntheticId(scenario, i + 1));
            state.setScenario(scenario);
            state.setCode(definition.code());
            state.setName(firstNonBlank(definition.name(), definition.code()));
            state.setDescription(definition.systemInstruction());
            state.setStatePrompt(definition.systemInstruction());
            state.setTransitionText(definition.transitionText());
            state.setDisplayOrder(i + 1);
            byCode.put(definition.code(), state);
        }
        for (ScenarioCompactDefinition.State definition : definitions) {
            ConversationState state = byCode.get(definition.code());
            if (state != null && definition.nextStateCode() != null) {
                state.setNextState(byCode.get(definition.nextStateCode()));
            }
        }
        return List.copyOf(byCode.values());
    }

    public ConversationState initialState(Scenario scenario) {
        List<ConversationState> states = states(scenario);
        String initial = flow(scenario).map(ScenarioCompactDefinition::initialState).orElse(null);
        return states.stream()
                .filter(state -> Objects.equals(state.getCode(), initial))
                .findFirst()
                .orElse(states.isEmpty() ? null : states.get(0));
    }

    public List<ScenarioHint> hints(Long scenarioId) {
        return hints(scenario(scenarioId));
    }

    public List<ScenarioHint> hints(Scenario scenario) {
        List<ScenarioCompactDefinition.Hint> definitions =
                flow(scenario).map(ScenarioCompactDefinition::hints).orElse(List.of());
        List<ScenarioHint> result = new ArrayList<>();
        for (int i = 0; i < definitions.size(); i++) {
            ScenarioCompactDefinition.Hint definition = definitions.get(i);
            ScenarioHint hint = new ScenarioHint();
            hint.setId(syntheticId(scenario, i + 1));
            hint.setScenario(scenario);
            hint.setTitle(definition.title());
            hint.setContent(definition.text());
            hint.setDisplayOrder(definition.displayOrder() != null ? definition.displayOrder() : i + 1);
            result.add(hint);
        }
        return List.copyOf(result);
    }

    public List<ScenarioEnding> endings(Scenario scenario) {
        Map<String, ScenarioCompactDefinition.Ending> definitions =
                flow(scenario).map(ScenarioCompactDefinition::endings).orElse(Map.of());
        List<ScenarioEnding> result = new ArrayList<>();
        int order = 1;
        for (ScenarioCompactDefinition.Ending definition : definitions.values()) {
            ScenarioEnding ending = new ScenarioEnding();
            ending.setId(syntheticId(scenario, order++));
            ending.setScenario(scenario);
            ending.setCondition(definition.condition());
            ending.setAiMessage(definition.message());
            ending.setNextAction(definition.nextAction());
            result.add(ending);
        }
        return List.copyOf(result);
    }

    public List<ScenarioVocabulary> vocabulary(Long scenarioId) {
        return vocabulary(scenario(scenarioId));
    }

    public List<ScenarioVocabulary> vocabulary(Scenario scenario) {
        List<ScenarioLearningContent.Vocabulary> definitions =
                learning(scenario).map(ScenarioLearningContent::vocabulary).orElse(List.of());
        List<ScenarioVocabulary> result = new ArrayList<>();
        for (int i = 0; i < definitions.size(); i++) {
            ScenarioLearningContent.Vocabulary definition = definitions.get(i);
            ScenarioVocabulary vocabulary = new ScenarioVocabulary();
            vocabulary.setId(syntheticId(scenario, i + 1));
            vocabulary.setScenario(scenario);
            vocabulary.setWordJa(definition.term());
            vocabulary.setReading(definition.reading());
            vocabulary.setMeaning(definition.meaning());
            vocabulary.setJlptLevel(definition.jlpt());
            vocabulary.setExampleJa(definition.example());
            vocabulary.setDisplayOrder(i + 1);
            result.add(vocabulary);
        }
        return List.copyOf(result);
    }

    public List<ScenarioGrammar> grammar(Scenario scenario) {
        List<ScenarioLearningContent.Grammar> definitions =
                learning(scenario).map(ScenarioLearningContent::grammar).orElse(List.of());
        List<ScenarioGrammar> result = new ArrayList<>();
        for (int i = 0; i < definitions.size(); i++) {
            ScenarioLearningContent.Grammar definition = definitions.get(i);
            ScenarioGrammar grammar = new ScenarioGrammar();
            grammar.setId(syntheticId(scenario, i + 1));
            grammar.setScenario(scenario);
            grammar.setPattern(definition.pattern());
            grammar.setExplanation(definition.explanation());
            grammar.setExampleJa(definition.example());
            grammar.setDisplayOrder(i + 1);
            result.add(grammar);
        }
        return List.copyOf(result);
    }

    public List<ExampleDialogue> dialogues(Scenario scenario) {
        List<ScenarioLearningContent.ExampleDialogue> definitions =
                learning(scenario).map(ScenarioLearningContent::exampleDialogue).orElse(List.of());
        List<ExampleDialogue> result = new ArrayList<>();
        for (int i = 0; i < definitions.size(); i++) {
            ScenarioLearningContent.ExampleDialogue definition = definitions.get(i);
            ExampleDialogue dialogue = new ExampleDialogue();
            dialogue.setId(syntheticId(scenario, i + 1));
            dialogue.setScenario(scenario);
            dialogue.setSpeaker(definition.speaker());
            dialogue.setContentJa(definition.text());
            dialogue.setTranslation(definition.translation());
            dialogue.setDisplayOrder(i + 1);
            result.add(dialogue);
        }
        return List.copyOf(result);
    }

    public List<String> suggestedReplies(Scenario scenario, ConversationState currentState) {
        ScenarioCompactDefinition definition = flow(scenario).orElse(null);
        if (definition == null || definition.states() == null) return List.of();
        String stateCode = currentState != null ? currentState.getCode() : definition.initialState();
        String stateName = currentState != null ? currentState.getName() : null;
        return definition.states().stream()
                .filter(state -> Objects.equals(state.code(), stateCode)
                        || (stateCode == null && Objects.equals(state.name(), stateName)))
                .findFirst()
                .map(ScenarioCompactDefinition.State::suggestedReplies)
                .filter(Objects::nonNull)
                .map(List::copyOf)
                .orElse(List.of());
    }

    private Optional<ScenarioCompactDefinition> flow(Scenario scenario) {
        return jsonMapper.readFlow(scenario.getFlowJson());
    }

    private Optional<ScenarioLearningContent> learning(Scenario scenario) {
        return jsonMapper.readLearningContent(scenario.getLearningContentJson());
    }

    private static Long syntheticId(Scenario scenario, int order) {
        return scenario.getId() * 1000 + order;
    }

    private static String firstNonBlank(String first, String fallback) {
        return first == null || first.isBlank() ? fallback : first;
    }
}
