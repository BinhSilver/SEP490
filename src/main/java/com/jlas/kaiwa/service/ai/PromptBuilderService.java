package com.jlas.kaiwa.service.ai;

import com.jlas.kaiwa.model.*;
import com.jlas.kaiwa.repository.*;
import com.jlas.kaiwa.service.scenario.ScenarioJsonMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds system prompts from DB template + scenario content.
 * Supports placeholder aliases used by {@code data.sql} and internal defaults.
 */
@Service
public class PromptBuilderService {

    private final ScenarioContextRepository contextRepository;
    private final ScenarioMissionRepository missionRepository;
    private final ScenarioObjectiveRepository objectiveRepository;
    private final ConversationStateRepository stateRepository;
    private final ScenarioVocabularyRepository vocabularyRepository;
    private final ScenarioGrammarRepository grammarRepository;
    private final ScenarioSuccessConditionRepository conditionRepository;
    private final ScenarioHintRepository hintRepository;
    private final FeedbackRuleRepository feedbackRuleRepository;
    private final ScenarioJsonMapper scenarioJsonMapper;

    public PromptBuilderService(
            ScenarioContextRepository contextRepository,
            ScenarioMissionRepository missionRepository,
            ScenarioObjectiveRepository objectiveRepository,
            ConversationStateRepository stateRepository,
            ScenarioVocabularyRepository vocabularyRepository,
            ScenarioGrammarRepository grammarRepository,
            ScenarioSuccessConditionRepository conditionRepository,
            ScenarioHintRepository hintRepository,
            FeedbackRuleRepository feedbackRuleRepository,
            ScenarioJsonMapper scenarioJsonMapper) {
        this.contextRepository = contextRepository;
        this.missionRepository = missionRepository;
        this.objectiveRepository = objectiveRepository;
        this.stateRepository = stateRepository;
        this.vocabularyRepository = vocabularyRepository;
        this.grammarRepository = grammarRepository;
        this.conditionRepository = conditionRepository;
        this.hintRepository = hintRepository;
        this.feedbackRuleRepository = feedbackRuleRepository;
        this.scenarioJsonMapper = scenarioJsonMapper;
    }

    /**
     * @deprecated Free-chat prompts are built by {@link FreeChatPromptComposer}.
     * Kept for compatibility; prefer FreeChatPromptComposer.compose(...).
     */
    @Deprecated
    public String buildFreeChatSystemPrompt(Scenario scenario) {
        return "Use FreeChatPromptComposer for free-chat prompts.";
    }

    @Transactional(readOnly = true)
    public String buildSystemPrompt(Scenario scenario, ConversationSession session, List<String> completedObjectives) {
        Long sid = scenario.getId();
        String template = scenario.getPromptTemplate() != null
                ? scenario.getPromptTemplate().getSystemPrompt()
                : defaultTemplate();

        ScenarioContext ctx = contextRepository.findByScenarioId(sid).orElse(null);
        ConversationState state = session.getCurrentState();
        AiRole aiRole = scenario.getAiRole();
        UserRole userRole = scenario.getUserRole();

        List<ScenarioMission> missionList = missionRepository.findByScenarioIdOrderByDisplayOrderAsc(sid);

        String missions = missionList.stream()
                .map(m -> "- " + m.getTitle() + (m.isRequired() ? " [required]" : " [optional]")
                        + (m.getDescription() != null ? ": " + m.getDescription() : ""))
                .collect(Collectors.joining("\n"));

        String missionTitles = missionList.stream()
                .map(ScenarioMission::getTitle)
                .collect(Collectors.joining(", "));

        String objectives = objectiveRepository.findByScenarioIdOrderByDisplayOrderAsc(sid).stream()
                .map(o -> "- [" + o.getCode() + "] " + o.getTitle()
                        + (completedObjectives.contains(o.getCode()) ? " (DONE)" : " (TODO)"))
                .collect(Collectors.joining("\n"));

        String flow = stateRepository.findByScenarioIdOrderByDisplayOrderAsc(sid).stream()
                .map(s -> s.getDisplayOrder() + ". " + s.getName() + " — " + n(s.getDescription()))
                .collect(Collectors.joining("\n"));

        String vocab = vocabularyRepository.findByScenarioIdOrderByDisplayOrderAsc(sid).stream()
                .map(v -> "- " + v.getWordJa() + " (" + n(v.getReading()) + "): " + v.getMeaning())
                .collect(Collectors.joining("\n"));

        String grammar = grammarRepository.findByScenarioIdOrderByDisplayOrderAsc(sid).stream()
                .map(g -> "- " + g.getPattern() + ": " + g.getExplanation())
                .collect(Collectors.joining("\n"));

        String conditions = conditionRepository.findByScenarioId(sid).stream()
                .map(c -> "- [" + c.getCode() + "] " + c.getConditionType() + "=" + n(c.getMatchValue()))
                .collect(Collectors.joining("\n"));

        String feedbackRules = feedbackRuleRepository.findByEnabledTrueOrderByPriorityAsc().stream()
                .map(r -> "- " + r.getName() + ": " + n(r.getDescription()))
                .collect(Collectors.joining("\n"));

        String hints = hintRepository.findByScenarioIdOrderByDisplayOrderAsc(sid).stream()
                .map(h -> "- " + (h.getTitle() != null ? h.getTitle() + ": " : "") + h.getContent())
                .collect(Collectors.joining("\n"));

        // Compact JSON overrides these rendered values during migration;
        // legacy repository values above remain the fallback.
        Optional<ScenarioCompactDefinition> compactFlow = scenarioJsonMapper.readFlow(scenario.getFlowJson());
        Optional<ScenarioLearningContent> compactLearning =
                scenarioJsonMapper.readLearningContent(scenario.getLearningContentJson());
        if (compactFlow.isPresent()) {
            ScenarioCompactDefinition definition = compactFlow.get();
            if (definition.goals() != null) {
                missions = definition.goals().stream()
                        .map(g -> "- " + n(g.title()) + (Boolean.TRUE.equals(g.required()) ? " [required]" : " [optional]")
                                + (g.description() != null ? ": " + g.description() : ""))
                        .collect(Collectors.joining("\n"));
                missionTitles = definition.goals().stream().map(ScenarioCompactDefinition.Goal::title)
                        .map(PromptBuilderService::n).collect(Collectors.joining(", "));
                objectives = definition.goals().stream()
                        .map(g -> "- [" + n(g.code()) + "] " + n(g.title())
                                + (completedObjectives.contains(g.code()) ? " (DONE)" : " (TODO)"))
                        .collect(Collectors.joining("\n"));
                conditions = definition.goals().stream()
                        .flatMap(g -> g.conditions() == null ? java.util.stream.Stream.empty() : g.conditions().stream()
                                .map(c -> "- [" + n(g.code()) + "] " + n(c.conditionType()) + "=" + n(c.matchValue())))
                        .collect(Collectors.joining("\n"));
            }
            if (definition.states() != null) {
                flow = definition.states().stream()
                        .map(s -> "- " + n(s.code()) + ": " + n(s.systemInstruction()))
                        .collect(Collectors.joining("\n"));
            }
            if (definition.hints() != null) {
                hints = definition.hints().stream()
                        .map(h -> "- " + (h.title() != null ? h.title() + ": " : "") + n(h.text()))
                        .collect(Collectors.joining("\n"));
            }
        }
        if (compactLearning.isPresent()) {
            ScenarioLearningContent content = compactLearning.get();
            if (content.vocabulary() != null) {
                vocab = content.vocabulary().stream()
                        .map(v -> "- " + n(v.term()) + " (" + n(v.reading()) + "): " + n(v.meaning()))
                        .collect(Collectors.joining("\n"));
            }
            if (content.grammar() != null) {
                grammar = content.grammar().stream()
                        .map(g -> "- " + n(g.pattern()) + ": " + n(g.explanation()))
                        .collect(Collectors.joining("\n"));
            }
        }

        String aiName = aiRole != null ? n(aiRole.getName()) : "会話相手";
        String aiPersonality = aiRole != null ? n(aiRole.getPersonality()) : "";
        String aiSpeech = aiRole != null ? n(aiRole.getSpeechStyle()) : "";
        String aiDefault = aiRole != null ? n(aiRole.getDefaultPrompt()) : "";
        String userName = userRole != null ? n(userRole.getName()) : "学習者";
        String userDesc = userRole != null ? n(userRole.getDescription()) : "";
        String background = ctx != null ? n(ctx.getBackground()) : n(scenario.getBackground());
        String location = ctx != null ? n(ctx.getLocation()) : n(scenario.getLocation());
        String time = ctx != null ? n(ctx.getTime()) : n(scenario.getTimeOfDay());
        String culture = ctx != null ? n(ctx.getCultureNote()) : n(scenario.getCultureNote());
        String stateName = state != null ? n(state.getName()) : "";
        String stateGoal = state != null ? n(state.getDescription()) : "";
        String statePrompt = state != null ? n(state.getStatePrompt()) : "";
        if (compactFlow.isPresent() && compactFlow.get().states() != null && !compactFlow.get().states().isEmpty()) {
            ScenarioCompactDefinition definition = compactFlow.get();
            String wantedState = state != null && state.getCode() != null
                    ? state.getCode() : definition.initialState();
            ScenarioCompactDefinition.State compactState = definition.states().stream()
                    .filter(s -> wantedState != null && wantedState.equals(s.code()))
                    .findFirst().orElse(definition.states().get(0));
            stateName = n(compactState.name() != null ? compactState.name() : compactState.code());
            statePrompt = n(compactState.systemInstruction());
            stateGoal = compactState.allowedGoalCodes() == null
                    ? "" : String.join(", ", compactState.allowedGoalCodes());
        }
        String completed = String.join(", ", completedObjectives);

        // Canonical keys + aliases used in data.sql / older templates
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("ai_role_name", aiName);
        vars.put("ai_personality", aiPersonality);
        vars.put("ai_role_personality", aiPersonality);
        vars.put("ai_speech_style", aiSpeech);
        vars.put("ai_role_speech_style", aiSpeech);
        vars.put("ai_default_prompt", aiDefault);
        vars.put("user_role_name", userName);
        vars.put("user_role_desc", userDesc);
        vars.put("user_role_description", userDesc);
        vars.put("scenario_title", n(scenario.getTitle()));
        vars.put("jlpt_level", n(scenario.getJlptLevel()));
        vars.put("difficulty", n(scenario.getDifficulty()));
        vars.put("background", background);
        vars.put("location", location);
        vars.put("time", time);
        vars.put("time_of_day", time);
        vars.put("culture_note", culture);
        vars.put("current_state", stateName);
        vars.put("state_name", stateName);
        vars.put("state_goal", stateGoal);
        vars.put("state_prompt", statePrompt);
        vars.put("missions", missions);
        vars.put("mission_titles", missionTitles);
        vars.put("objectives", objectives);
        vars.put("completed_objectives", completed);
        vars.put("flow", flow);
        vars.put("vocabulary", vocab);
        vars.put("grammar", grammar);
        vars.put("success_conditions", conditions);
        vars.put("feedback_rules", feedbackRules);
        vars.put("hints", hints);

        String built = applyPlaceholders(template, vars);
        // Always append hard rules so old DB templates still avoid the "もう一度" loop
        return built + """

                ---
                BẮT BUỘC (override):
                - reply = tiếng Nhật trong vai, tiếp tục hội thoại theo state/mission.
                - CẤM reply chỉ là 「すみません、もう一度お願いします」hoặc tương đương.
                - Người học nói sai/Việt/không rõ  hỏi lại bằng câu đơn giản (お名前は？どちらから？) và ghi lỗi ở hasError.
                - Nếu người học sai: hasError=true, originalWrong=nguyên văn, corrected=câu Nhật đúng,
                  grammarReason=giải thích tiếng Việt cụ thể. Không giảng hay chèn câu sửa vào reply.
                - reply gồm phần phản hồi trong vai và tối đa một câu hỏi tiếp theo; đặt câu hỏi đó trên dòng mới.
                - Bạn là nhân vật trong tình huống, không phải giáo viên đang kiểm tra. CẤM hỏi kiểu
                  「何と言いますか」「どう言いますか」「言ってみてください」để bắt người học đọc đáp án.
                - Khi state hiện tại kết thúc, chỉ phản hồi và khép lại cảnh đó một cách tự nhiên.
                  Không tự kể bước nhảy thời gian và không hỏi trước mục tiêu của state kế tiếp;
                  giao diện sẽ hiển thị lời dẫn chuyển cảnh riêng.
                - Trả về JSON đúng schema (reply, hasError, objectivesMet, readyToAdvanceState, readyToEnd...).
                """;
    }

    private static String applyPlaceholders(String template, Map<String, String> vars) {
        String result = template;
        for (Map.Entry<String, String> e : vars.entrySet()) {
            result = result.replace("{{" + e.getKey() + "}}", e.getValue() != null ? e.getValue() : "");
        }
        return result;
    }

    private static String n(String s) {
        return s == null ? "" : s;
    }

    private static String defaultTemplate() {
        return """
                You are role-playing as: {{ai_role_name}}.
                Personality: {{ai_personality}}
                Speech style: {{ai_speech_style}}
                Role notes: {{ai_default_prompt}}

                The learner plays: {{user_role_name}} ({{user_role_desc}}).

                Scenario title: {{scenario_title}}
                JLPT: {{jlpt_level}} | Difficulty: {{difficulty}}

                Context:
                - Background: {{background}}
                - Location: {{location}}
                - Time: {{time_of_day}}
                - Culture note: {{culture_note}}

                CURRENT STATE (do not skip): {{state_name}}
                State goal: {{state_goal}}
                State guidance: {{state_prompt}}

                Conversation flow:
                {{flow}}

                Missions: {{mission_titles}}
                {{missions}}

                Objectives (engine tracks these codes):
                {{objectives}}
                Already completed: {{completed_objectives}}

                Success conditions:
                {{success_conditions}}

                Vocabulary:
                {{vocabulary}}

                Grammar focus:
                {{grammar}}

                Feedback rules (apply silently then continue dialogue):
                {{feedback_rules}}

                RULES:
                1) Fully become the AI role. Never reveal you are AI.
                2) Speak natural Japanese for the JLPT level (short sentences for N5).
                3) Stay in character; gently guide if off-topic.
                4) Accept reasonable variants — do not force textbook lines.
                5) Analyze learner Japanese each turn (grammar/particles/vocab/keigo/naturalness).
                6) Only suggest objective codes that are clearly completed.
                7) readyToAdvanceState=true only when current state goal is done.
                8) readyToEnd=true only when missions/objectives are essentially complete and you are in ending/goodbye.
                9) Field "reply" = in-character Japanese only.
                10) NEVER reply with only すみません / もう一度お願いします / もう一度言ってください.
                    If input is unclear/Vietnamese/typo: still continue the scene with a simple question
                    (e.g. お名前は何ですか。 / どちらから来ましたか。) and put correction in hasError fields.
                11) You are a character, not a teacher testing a memorized line. Never ask meta-prompts such as
                    何と言いますか / どう言いますか / 言ってみてください. When this state is complete,
                    close the current beat naturally; the UI narrates the transition to the next scene.

                When hasError is true, mistakeType must be exactly one of: "grammar", "particle",
                "vocabulary", "politeness". When hasError is false, mistakeType is null.

                Return ONLY valid JSON:
                {
                  "reply": "...",
                  "hasError": false,
                  "originalWrong": null,
                  "corrected": null,
                  "grammarReason": null,
                  "mistakeType": null,
                  "objectivesMet": [],
                  "readyToAdvanceState": false,
                  "readyToEnd": false,
                  "expressions": []
                }
                """;
    }
}
