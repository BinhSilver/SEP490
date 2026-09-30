package com.jlas.kaiwa.service.scenario;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jlas.kaiwa.model.SessionStatus;
import com.jlas.kaiwa.model.*;
import com.jlas.kaiwa.repository.*;
import com.jlas.kaiwa.runtime.ConversationRuntimeMessage;
import com.jlas.kaiwa.runtime.ConversationRuntimeState;
import com.jlas.kaiwa.runtime.ConversationRuntimeStore;
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
        Objects.requireNonNull(userId, "Authenticated userId is required");
        ConversationSession session = new ConversationSession();
        session.setScenario(scenario);
        session.setUserId(userId);
        session.setStatus(SessionStatus.IN_PROGRESS);
        session.setCompletedObjectives("[]");
        session.setStartedAt(LocalDateTime.now());
        session.setCurrentState(scenarioContent.initialState(scenario));
        ConversationSession saved = sessionRepository.save(session);
        writeRuntime(saved, Set.of(), List.of());
        return saved;
    }

    public List<String> parseCompleted(ConversationSession session) {
        Optional<ConversationRuntimeState> runtime = runtimeStore.get(session.getId());
        if (runtime.isPresent()) {
            restoreCurrentState(session, runtime.get().currentStateCode());
            return new ArrayList<>(runtime.get().completedGoalCodes() == null
                    ? Set.of() : runtime.get().completedGoalCodes());
        }
        restoreCurrentState(session, null);
        try {
            if (session.getCompletedObjectives() == null || session.getCompletedObjectives().isBlank()) {
                return new ArrayList<>();
            }
            return objectMapper.readValue(session.getCompletedObjectives(), new TypeReference<>() {});
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
        Long scenarioId = session.getScenario().getId();
        Set<String> done = new LinkedHashSet<>(parseCompleted(session));
        List<ScenarioSuccessCondition> conditions = scenarioContent.successConditions(session.getScenario());
        String text = learnerText == null ? "" : learnerText;

        for (ScenarioSuccessCondition c : conditions) {
            if (done.contains(c.getCode())) continue;
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
                if (valid.contains(code)) done.add(code);
            }
        }

        List<String> result = new ArrayList<>(done);
        writeCompleted(session, result);
        return result;
    }

    public boolean allRequiredObjectivesDone(Long scenarioId, List<String> completed) {
        List<ScenarioObjective> required = scenarioContent.objectives(scenarioId)
                .stream().filter(ScenarioObjective::isRequired).toList();
        if (required.isEmpty()) {
            return !completed.isEmpty();
        }
        return required.stream().allMatch(o -> completed.contains(o.getCode()));
    }

    public int progressPercent(Long scenarioId, List<String> completed) {
        List<ScenarioObjective> all = scenarioContent.objectives(scenarioId);
        if (all.isEmpty()) return completed.isEmpty() ? 0 : 50;
        long done = all.stream().filter(o -> completed.contains(o.getCode())).count();
        return (int) Math.min(100, Math.round(100.0 * done / all.size()));
    }

    public List<String> objectiveTitles(Long scenarioId, List<String> completed, boolean doneOnly) {
        return scenarioContent.objectives(scenarioId).stream()
                .filter(o -> doneOnly == completed.contains(o.getCode()))
                .map(ScenarioObjective::getTitle)
                .toList();
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
        runtimeStore.put(new ConversationRuntimeState(
                session.getId(),
                session.getScenario().getId(),
                state == null ? null : state.getCode(),
                Set.copyOf(completed),
                List.copyOf(messages == null ? List.of() : messages),
                Instant.now()));
    }
}
