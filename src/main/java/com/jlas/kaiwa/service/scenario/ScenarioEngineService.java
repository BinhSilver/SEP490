package com.jlas.kaiwa.service.scenario;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jlas.kaiwa.model.SessionStatus;
import com.jlas.kaiwa.model.*;
import com.jlas.kaiwa.repository.*;
import com.jlas.kaiwa.runtime.ConversationRuntimeMessage;
import com.jlas.kaiwa.runtime.ConversationRuntimeState;
import com.jlas.kaiwa.runtime.ConversationRuntimeStore;
import com.jlas.kaiwa.util.StageFlowSupport;
import java.time.Instant;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns mission/objective progress, state transitions, ending conditions.
 * AI must not control these rules alone.
 */
@Service
public class ScenarioEngineService {

    private final ConversationSessionRepository sessionRepository;
    private final ScenarioContentService scenarioContent;
    private final ConversationRuntimeStore runtimeStore;
    private final ObjectMapper objectMapper;

    public ScenarioEngineService(
            ConversationSessionRepository sessionRepository,
            ScenarioContentService scenarioContent,
            ConversationRuntimeStore runtimeStore,
            ObjectMapper objectMapper) {
        this.sessionRepository = sessionRepository;
        this.scenarioContent = scenarioContent;
        this.runtimeStore = runtimeStore;
        this.objectMapper = objectMapper;
    }

    /** Starts a session owned by the authenticated user. */
    @Transactional
    public ConversationSession startSession(Scenario scenario, Long userId) {
        return startSession(scenario,userId,null);
    }

    @Transactional
    public ConversationSession startSession(Scenario scenario, Long userId, ScenarioStage stage) {
        Objects.requireNonNull(userId, "Authenticated userId is required");
        ConversationSession session = new ConversationSession();
        session.setScenario(scenario);
        session.setUserId(userId);
        session.setStatus(SessionStatus.IN_PROGRESS);
        session.setCompletedObjectives("[]");
        session.setStartedAt(LocalDateTime.now());
        session.setCurrentState(scenarioContent.initialState(scenario));
        if(stage!=null) {
            session.setStageCode(stage.code());
            try {session.setStageDefinitionJson(objectMapper.writeValueAsString(stage));}
            catch(Exception e){throw new IllegalStateException(e);}
        }
        ConversationSession saved = sessionRepository.save(session);
        if (scenarioContent.isGraph(scenario)) {
            writeCompleted(saved, graphMarkers(saved, Set.of()));
        } else {
            writeRuntime(saved, Set.of(), List.of());
        }
        return saved;
    }

    public List<String> parseCompleted(ConversationSession session) {
        Optional<ConversationRuntimeState> runtime = runtimeStore.get(session.getId());
        if (runtime.isPresent()) {
            restoreCurrentState(session, runtime.get().currentStateCode());
            return new ArrayList<>(runtime.get().completedGoalCodes() == null
                    ? Set.of() : runtime.get().completedGoalCodes());
        }
        restoreCurrentState(session, session.getCurrentStateCode());
        try {
            if (session.getCompletedObjectives() == null || session.getCompletedObjectives().isBlank()) {
                return new ArrayList<>();
            }
            List<String> persisted = objectMapper.readValue(session.getCompletedObjectives(), new TypeReference<>() {});
            persisted.stream().filter(c -> c.startsWith("@CURRENT:")).findFirst()
                    .ifPresent(c -> restoreCurrentState(session, c.substring(9)));
            writeRuntime(session,new LinkedHashSet<>(persisted),List.of());
            return persisted;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    public void writeCompleted(ConversationSession session, List<String> codes) {
        try {
            session.setCompletedObjectives(objectMapper.writeValueAsString(codes));
        } catch (Exception e) {
            session.setCompletedObjectives("[]");
        }
        List<ConversationRuntimeMessage> messages = runtimeStore.get(session.getId())
                .map(ConversationRuntimeState::messages).orElse(List.of());
        writeRuntime(session, new LinkedHashSet<>(codes), messages);
    }

    @Transactional
    public List<String> evaluateObjectives(ConversationSession session, String learnerText, List<String> aiSuggested) {
        return evaluateObjectives(session, learnerText, aiSuggested, null);
    }

    @Transactional
    public List<String> evaluateObjectives(ConversationSession session, String learnerText, List<String> aiSuggested, String proposedTarget) {
        Long scenarioId = session.getScenario().getId();
        Set<String> done = new LinkedHashSet<>(parseCompleted(session));
        List<ScenarioSuccessCondition> conditions = scenarioContent.successConditions(session.getScenario());
        String text = learnerText == null ? "" : learnerText;
        boolean graph = scenarioContent.isGraph(session.getScenario());
        Set<String> allowed = graph ? new HashSet<>(scenarioContent.allowedGoals(session.getScenario(),
                session.getCurrentState() == null ? null : session.getCurrentState().getCode())) : Set.of();
        if (graph && proposedTarget != null && session.getCurrentState() != null) {
            // A request can open a branch and fulfill its first goal in the same
            // utterance, e.g. asking about eggs while choosing a dish. Do not
            // force the learner to repeat that question after the transition.
            Set<String> available = new HashSet<>(done);
            if (aiSuggested != null) aiSuggested.stream().filter(allowed::contains).forEach(available::add);
            scenarioContent.flow(session.getScenario()).orElseThrow().edges().stream()
                    .filter(e -> Objects.equals(e.source(), session.getCurrentState().getCode()) && Objects.equals(e.target(), proposedTarget))
                    .filter(e -> StageFlowSupport.permits(session,e.source(),e.target()))
                    .filter(e -> e.requiredGoalCodes() == null || available.containsAll(e.requiredGoalCodes()))
                    .findFirst().ifPresent(e -> {
                        if (e.entryGoalCodes() != null) allowed.addAll(e.entryGoalCodes());
                    });
        }

        for (ScenarioSuccessCondition c : conditions) {
            if (done.contains(c.getCode()) || (graph && !allowed.contains(c.getCode()))) continue;
            boolean hit = false;
            if ("KEYWORD".equalsIgnoreCase(c.getConditionType()) && c.getMatchValue() != null) {
                for (String kw : c.getMatchValue().split("[,|]")) {
                    String k = kw.trim();
                    if (!k.isEmpty() && text.contains(k)) {
                        hit = true;
                        break;
                    }
                }
            }
            if ("OBJECTIVE_FLAG".equalsIgnoreCase(c.getConditionType()) && aiSuggested != null) {
                if (aiSuggested.contains(c.getCode())
                        || (c.getMatchValue() != null && aiSuggested.contains(c.getMatchValue().trim()))) {
                    hit = true;
                }
            }
            if (hit) {
                done.add(c.getCode());
                if (c.getObjective() != null) {
                    done.add(c.getObjective().getCode());
                }
            }
        }

        if (aiSuggested != null) {
            Set<String> valid = scenarioContent.objectives(session.getScenario()).stream()
                    .map(ScenarioObjective::getCode)
                    .collect(Collectors.toSet());
            for (String code : aiSuggested) {
                if (valid.contains(code) && (!graph || allowed.contains(code))) done.add(code);
            }
        }

        List<String> result = new ArrayList<>(done);
        writeCompleted(session, result);
        return result;
    }

    public boolean allRequiredObjectivesDone(Long scenarioId, List<String> completed) {
        Scenario scenario = scenarioContent.scenario(scenarioId);
        if (scenarioContent.isGraph(scenario)) {
            var flow = scenarioContent.flow(scenario).orElseThrow();
            boolean terminal = flow.terminalStateCodes().stream().anyMatch(c -> completed.contains("@CURRENT:" + c));
            return terminal && trackedObjectives(scenarioId, completed).stream().allMatch(o -> completed.contains(o.getCode()));
        }
        List<ScenarioObjective> required = scenarioContent.objectives(scenarioId)
                .stream().filter(ScenarioObjective::isRequired).toList();
        if (required.isEmpty()) {
            return !completed.isEmpty();
        }
        return required.stream().allMatch(o -> completed.contains(o.getCode()));
    }

    public int progressPercent(Long scenarioId, List<String> completed) {
        List<ScenarioObjective> all = trackedObjectives(scenarioId, completed);
        if (all.isEmpty()) return completed.isEmpty() ? 0 : 50;
        long done = all.stream().filter(o -> completed.contains(o.getCode())).count();
        return (int) Math.min(100, Math.round(100.0 * done / all.size()));
    }

    public List<String> objectiveTitles(Long scenarioId, List<String> completed, boolean doneOnly) {
        return trackedObjectives(scenarioId, completed).stream()
                .filter(o -> doneOnly == completed.contains(o.getCode()))
                .map(ScenarioObjective::getTitle)
                .toList();
    }

    public List<ScenarioObjective> trackedObjectives(Long id, List<String> completed) {
        Scenario scenario = scenarioContent.scenario(id);
        if (!scenarioContent.isGraph(scenario)) return scenarioContent.objectives(scenario);
        Set<String> visitedGoals = new HashSet<>();
        completed.stream().filter(c->c.startsWith("@REQUIRED:")).forEach(c->visitedGoals.add(c.substring(10)));
        if(!visitedGoals.isEmpty())return scenarioContent.objectives(scenario).stream().filter(o->visitedGoals.contains(o.getCode())).toList();
        completed.stream().filter(c -> c.startsWith("@VISIT:"))
                .forEach(c -> visitedGoals.addAll(scenarioContent.allowedGoals(scenario, c.substring(7))));
        return scenarioContent.objectives(scenario).stream().filter(o -> visitedGoals.contains(o.getCode())).toList();
    }

    private List<String> graphMarkers(ConversationSession session, Set<String> completed) {
        Set<String> codes = new LinkedHashSet<>(completed);
        var stage=StageFlowSupport.stage(session);
        if(stage!=null)stage.requiredGoalCodes().forEach(c->codes.add("@REQUIRED:"+c));
        codes.removeIf(c -> c.startsWith("@CURRENT:"));
        if (session.getCurrentState() != null) {
            codes.add("@VISIT:" + session.getCurrentState().getCode());
            codes.add("@CURRENT:" + session.getCurrentState().getCode());
        }
        return new ArrayList<>(codes);
    }

    /** Whitelisted edge; goal evidence accumulates during this visit to a node. */
    @Transactional
    public ConversationState advanceGraph(ConversationSession session, boolean ready, String target, List<String> turnGoals) {
        if (!scenarioContent.isGraph(session.getScenario()) || !ready || target == null) return session.getCurrentState();
        List<String> completed = parseCompleted(session);
        ConversationState current = session.getCurrentState();
        if (current == null) return null;
        var flow = scenarioContent.flow(session.getScenario()).orElseThrow();
        var edge = flow.edges().stream().filter(e -> Objects.equals(e.source(), current.getCode()) && Objects.equals(e.target(), target))
                .findFirst().orElse(null);
        if (edge == null) return current;
        if(!StageFlowSupport.permits(session,current.getCode(),target))return current;
        if (edge.requiredGoalCodes() != null && edge.requiredGoalCodes().stream()
                .anyMatch(c -> !completed.contains(c))) return current;
        ConversationState next = scenarioContent.states(session.getScenario()).stream()
                .filter(s -> Objects.equals(s.getCode(), target)).findFirst().orElse(null);
        if (next == null) return current;
        // A new visit must complete its own order/confirmation, while a decision
        // over several turns in the same visit can reuse already-earned evidence.
        Set<String> fresh = turnGoals == null ? Set.of() : new HashSet<>(turnGoals);
        fresh.retainAll(edge.entryGoalCodes() == null ? List.of() : edge.entryGoalCodes());
        scenarioContent.allowedGoals(session.getScenario(), next.getCode()).stream()
                .filter(c -> !fresh.contains(c)).forEach(completed::remove);
        session.setCurrentState(next);
        StageFlowSupport.advance(session,target);
        writeCompleted(session, graphMarkers(session, new LinkedHashSet<>(completed)));
        return next;
    }

    public List<String> allObjectiveTitles(Long scenarioId) {
        return scenarioContent.objectives(scenarioId).stream()
                .map(ScenarioObjective::getTitle)
                .toList();
    }

    public List<ScenarioObjective> allObjectives(Long scenarioId) {
        return scenarioContent.objectives(scenarioId);
    }

    @Transactional
    public ConversationState maybeAdvanceState(ConversationSession session, boolean aiReady, boolean objectivesDone) {
        if (scenarioContent.isGraph(session.getScenario())) return session.getCurrentState();
        ConversationState current = session.getCurrentState();
        if (current == null) {
            restoreCurrentState(session, runtimeStore.get(session.getId())
                    .map(ConversationRuntimeState::currentStateCode).orElse(null));
            current = session.getCurrentState();
        }
        if (current == null) return null;

        boolean advance = aiReady;
        String name = current.getName() == null ? "" : current.getName().toLowerCase(Locale.ROOT);
        if (objectivesDone && (name.contains("mission") || name.contains("order") || name.contains("payment")
                || name.contains("confirm"))) {
            advance = true;
        }
        if (!advance) return current;

        ConversationState next = current.getNextState();
        if (next == null) {
            // fallback: next by display_order
            List<ConversationState> states = scenarioContent.states(session.getScenario());
            for (int i = 0; i < states.size(); i++) {
                if (Objects.equals(states.get(i).getCode(), current.getCode()) && i + 1 < states.size()) {
                    next = states.get(i + 1);
                    break;
                }
            }
        }
        if (next != null) {
            Set<String> completed = runtimeStore.get(session.getId())
                    .map(ConversationRuntimeState::completedGoalCodes)
                    .map(LinkedHashSet::new)
                    .orElseGet(LinkedHashSet::new);
            List<ConversationRuntimeMessage> messages = runtimeStore.get(session.getId())
                    .map(ConversationRuntimeState::messages).orElse(List.of());
            session.setCurrentState(next);
            writeRuntime(session, completed, messages);
            return next;
        }
        return current;
    }

    public Optional<ScenarioEnding> findEnding(Long scenarioId, boolean objectivesDone) {
        List<ScenarioEnding> endings = scenarioContent.endings(scenarioContent.scenario(scenarioId));
        if (objectivesDone) {
            return endings.stream()
                    .filter(e -> "mission_completed".equalsIgnoreCase(e.getCondition())
                            || "all_objectives".equalsIgnoreCase(e.getCondition()))
                    .findFirst()
                    .or(() -> endings.stream().findFirst());
        }
        return Optional.empty();
    }

    @Transactional
    public ConversationSession completeSession(ConversationSession session) {
        if(session.getStageCode()!=null)session.setStagePassed(!session.isContentReview()&&StageFlowSupport.atEnd(session)
                &&allRequiredObjectivesDone(session.getScenario().getId(),parseCompleted(session)));
        session.setStatus(SessionStatus.COMPLETED);
        session.setEndedAt(LocalDateTime.now());
        if (session.getStartedAt() != null) {
            session.setDurationSeconds((int) Duration.between(session.getStartedAt(), session.getEndedAt()).getSeconds());
        }
        return sessionRepository.save(session);
    }

    @Transactional(readOnly = true)
    public String nextHint(Long scenarioId, int index) {
        List<ScenarioHint> hints = scenarioContent.hints(scenarioId);
        if (hints.isEmpty()) {
            return "Hãy nói tự nhiên theo vai trò. Tập trung vào mission hiện tại.";
        }
        int i = Math.min(Math.max(0, index - 1), hints.size() - 1);
        ScenarioHint h = hints.get(i);
        return (h.getTitle() != null ? h.getTitle() + ": " : "") + h.getContent();
    }

    private void restoreCurrentState(ConversationSession session, String stateCode) {
        List<ConversationState> states = scenarioContent.states(session.getScenario());
        ConversationState state = states.stream()
                .filter(candidate -> Objects.equals(candidate.getCode(), stateCode))
                .findFirst()
                .orElseGet(() -> scenarioContent.initialState(session.getScenario()));
        session.setCurrentState(state);
    }

    private void writeRuntime(
            ConversationSession session,
            Set<String> completed,
            List<ConversationRuntimeMessage> messages) {
        ConversationState state = session.getCurrentState();
        session.setCurrentStateCode(state==null?null:state.getCode());
        session.setLastActivityAt(LocalDateTime.now());
        try {session.setCompletedObjectives(objectMapper.writeValueAsString(completed));}
        catch(Exception e){throw new IllegalStateException(e);}
        runtimeStore.put(new ConversationRuntimeState(
                session.getId(),
                session.getScenario().getId(),
                state == null ? null : state.getCode(),
                Set.copyOf(completed),
                List.copyOf(messages == null ? List.of() : messages),
                Instant.now()));
    }
}
