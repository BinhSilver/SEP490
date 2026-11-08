package com.jlas.kaiwa.controller;

import com.jlas.kaiwa.model.*;
import com.jlas.kaiwa.repository.*;
import com.jlas.kaiwa.dto.FuriganaTokenDto;
import com.jlas.kaiwa.service.FreeChatSessionService;
import com.jlas.kaiwa.service.ai.AIConversationService;
import com.jlas.kaiwa.service.learning.AnnotationService;
import com.jlas.kaiwa.service.learning.SuggestedReplyService;
import com.jlas.kaiwa.service.scenario.ScenarioEngineService;
import com.jlas.kaiwa.service.scenario.ScenarioContentService;
import com.jlas.kaiwa.service.scenario.SceneTransitionService;
import com.jlas.kaiwa.service.scenario.ConversationSpeakerService;
import com.jlas.kaiwa.util.StageFlowSupport;
import com.jlas.kaiwa.util.SceneImageSupport;
import com.jlas.common.exception.*;
import com.jlas.auth.security.UserPrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Controller;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
public class ConversationPageController {

    private final AIConversationService conversationService;
    private final ConversationSessionRepository sessionRepository;
    private final ConversationMessageRepository messageRepository;
    private final SessionScoreRepository scoreRepository;
    private final TurnFeedbackRepository feedbackRepository;
    private final ScenarioEngineService scenarioEngine;
    private final ScenarioContentService scenarioContent;
    private final AnnotationService annotationService;
    private final SuggestedReplyService suggestedReplyService;
    private final SceneTransitionService sceneTransitionService;
    private final ObjectMapper objectMapper;
    private final ConversationSpeakerService speakers;
    private final FreeChatSessionService freeChatSessions;

    public ConversationPageController(
            AIConversationService conversationService,
            ConversationSessionRepository sessionRepository,
            ConversationMessageRepository messageRepository,
            SessionScoreRepository scoreRepository,
            TurnFeedbackRepository feedbackRepository,
            ScenarioEngineService scenarioEngine,
            ScenarioContentService scenarioContent,
            AnnotationService annotationService,
            SuggestedReplyService suggestedReplyService,
            SceneTransitionService sceneTransitionService,
            ObjectMapper objectMapper, ConversationSpeakerService speakers, FreeChatSessionService freeChatSessions) {
        this.conversationService = conversationService;
        this.freeChatSessions = freeChatSessions;
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.scoreRepository = scoreRepository;
        this.feedbackRepository = feedbackRepository;
        this.scenarioEngine = scenarioEngine;
        this.scenarioContent = scenarioContent;
        this.annotationService = annotationService;
        this.suggestedReplyService = suggestedReplyService;
        this.sceneTransitionService = sceneTransitionService;
        this.objectMapper = objectMapper;
        this.speakers = speakers;
    }

    /** GET + POST: start conversation without CSRF/session issues on form render. */
    @RequestMapping(value = "/scenario/{scenarioId}/start", method = {RequestMethod.GET, RequestMethod.POST})
    public String start(@PathVariable Long scenarioId,
                        @AuthenticationPrincipal UserPrincipal principal,
                        @RequestParam(required=false) String stage) {
        ConversationSession session;
        try {session=conversationService.start(scenarioId, principal.userId(),stage);}
        catch(ApiException e) {
            if(e.getErrorCode()!=ErrorCode.AICALL_STAGE_LOCKED)throw e;
            return "redirect:/scenario/"+scenarioId+"?locked=1";
        }
        return "redirect:/conversation/" + session.getId();
    }

    /** AI Tán Gẫu — free chat using the same call engine; continues today's session when there is one. */
    @RequestMapping(value = "/ai-call/free-chat/start", method = {RequestMethod.GET, RequestMethod.POST})
    public String startFreeChat(@AuthenticationPrincipal UserPrincipal principal) {
        ConversationSession session = freeChatSessions.open(principal.userId());
        return "redirect:/conversation/" + session.getId();
    }

    @GetMapping("/conversation/{sessionId}")
    public String conversation(@PathVariable Long sessionId, @AuthenticationPrincipal UserPrincipal principal,
                               Model model) throws Exception {
        ConversationSession session = ownedSession(sessionId, principal);
        if (session == null) {
            return "redirect:/ai-call";
        }
        Scenario scenario = session.getScenario();
        Long scenarioId = scenario.getId();
        boolean freeChat = FreeChatSupport.isFreeChat(scenario);
        // A free chat still open from an earlier day gives way to today's.
        if (freeChat && session.getStatus() == SessionStatus.IN_PROGRESS
                && !FreeChatSessionService.current(session, LocalDateTime.now())) {
            return "redirect:/ai-call/free-chat/start";
        }
        model.addAttribute("stageDefinition",StageFlowSupport.stage(session));
        List<ConversationMessage> messages = messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
        if (messages.isEmpty() && session.getStatus() == SessionStatus.IN_PROGRESS) {
            conversationService.ensureOpeningMessage(session);
            messages = messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
        }

        List<String> completed = freeChat ? List.of() : scenarioEngine.parseCompleted(session);
        var cast = freeChat ? List.<ScenarioSpeaker>of() : speakers.cast(session);
        var currentSpeaker = freeChat ? null : speakers.current(session);
        Map<Long, ScenarioSpeaker> messageSpeakers = new HashMap<>();
        for (var message : messages) {
            var actor = speakers.forMessage(message);
            if (actor != null) messageSpeakers.put(message.getId(), actor);
        }
        model.addAttribute("scenarioSpeakers", cast);
        model.addAttribute("currentSpeaker", currentSpeaker);
        model.addAttribute("speakerByMessageId", messageSpeakers);
        model.addAttribute("hasCast", !cast.isEmpty());
        model.addAttribute("hasTeacherVoice", cast.stream().anyMatch(s -> s.voiceVariants() != null && !s.voiceVariants().isEmpty()));
        model.addAttribute("legacyAiRoleName", scenario.getAiRole() != null ? scenario.getAiRole().getName() : "AI");
        List<ScenarioObjective> objectives = freeChat
                ? List.of()
                : scenarioEngine.trackedObjectives(scenarioId, completed);
        List<ScenarioVocabulary> vocab = freeChat
                ? List.of()
                : scenarioContent.vocabulary(scenario);
        List<ScenarioGrammar> grammar = freeChat
                ? List.of()
                : scenarioContent.grammar(scenario);
        List<ScenarioMission> missions = freeChat
                ? List.of()
                : scenarioContent.missions(scenario);

        Map<String, Object> vocabJson = new HashMap<>();
        vocabJson.put("items", vocab.stream().map(v -> {
            Map<String, Object> m = new HashMap<>();
            m.put("wordJa", v.getWordJa());
            m.put("reading", v.getReading());
            m.put("meaning", v.getMeaning());
            m.put("partOfSpeech", v.getPartOfSpeech());
            m.put("jlptLevel", v.getJlptLevel());
            m.put("exampleJa", v.getExampleJa());
            return m;
        }).toList());

        // IMPORTANT: do not use model name "session" — Thymeleaf reserves it for HttpSession
        boolean inProgress = session.getStatus() != null
                && "IN_PROGRESS".equals(session.getStatus().name());
        model.addAttribute("chatSession", session);
        model.addAttribute("freeChat", freeChat);
        model.addAttribute("inProgress", inProgress);
        model.addAttribute("scenario", scenario);
        model.addAttribute("messages", messages);
        Map<Long, List<FuriganaTokenDto>> furiganaByMessageId = new HashMap<>();
        Map<Long, Boolean> furiganaCompleteByMessageId = new HashMap<>();
        if (!freeChat) {
            for (ConversationMessage message : messages) {
                if (!"assistant".equals(message.getRole()) || message.getId() == null) continue;
                List<FuriganaTokenDto> tokens = annotationService.annotateFurigana(
                        scenarioId, message.getContent(), false);
                furiganaByMessageId.put(message.getId(), tokens);
                furiganaCompleteByMessageId.put(message.getId(),
                        tokens.stream().allMatch(FuriganaTokenDto::isKanjiReadingComplete));
            }
        }
        model.addAttribute("furiganaByMessageId", furiganaByMessageId);
        model.addAttribute("furiganaCompleteByMessageId", furiganaCompleteByMessageId);
        Map<Long, String> sceneTransitionByMessageId = new HashMap<>();
        ConversationState previousMessageState = null;
        if (!freeChat) {
            // Transcript nạp từ DB chỉ có state_code; dựng lại state từ flow_json của kịch bản.
            Map<String, ConversationState> statesByCode = new HashMap<>();
            for (ConversationState state : scenarioContent.states(scenario)) {
                if (state.getCode() != null) statesByCode.put(state.getCode(), state);
            }
            for (ConversationMessage message : messages) {
                ConversationState messageState = message.getState() != null
                        ? message.getState()
                        : statesByCode.get(message.getStateCode());
                if ("assistant".equals(message.getRole()) && message.getId() != null) {
                    String transition = sceneTransitionService.describe(
                            scenario, previousMessageState, messageState);
                    if (transition != null && !transition.isBlank()) {
                        sceneTransitionByMessageId.put(message.getId(), transition);
                    }
                }
                if (messageState != null) previousMessageState = messageState;
            }
        }
        model.addAttribute("sceneTransitionByMessageId", sceneTransitionByMessageId);
        Map<Long, TurnFeedback> feedbackByMessageId = new HashMap<>();
        for (TurnFeedback feedback : feedbackRepository.findBySessionIdOrderByCreatedAtAsc(sessionId)) {
            if (feedback.getMessage() != null && feedback.getMessage().getId() != null) {
                feedbackByMessageId.put(feedback.getMessage().getId(), feedback);
            }
        }
        model.addAttribute("feedbackByMessageId", feedbackByMessageId);
        model.addAttribute("progressPercent", freeChat ? 0 : scenarioEngine.progressPercent(scenarioId, completed));
        model.addAttribute("completedObjectives", freeChat
                ? List.of()
                : scenarioEngine.objectiveTitles(scenarioId, completed, true));
        model.addAttribute("remainingObjectives", freeChat
                ? List.of()
                : scenarioEngine.objectiveTitles(scenarioId, completed, false));
        model.addAttribute("objectives", objectives);
        model.addAttribute("completedCodes", completed);
        model.addAttribute("missions", missions);
        model.addAttribute("vocabulary", vocab);
        model.addAttribute("grammar", grammar);
        model.addAttribute("vocabJson", objectMapper.writeValueAsString(vocabJson));
        model.addAttribute("currentStateName", freeChat
                ? "Tán gẫu"
                : (session.getCurrentState() != null ? session.getCurrentState().getName() : ""));
        // DB-only suggestions on page load (no AI) — free chat: never
        model.addAttribute("suggestedReplies",
                (!freeChat && inProgress) ? suggestedReplyService.suggestionsFromDbOnly(session) : List.of());
        model.addAttribute("aiRoleName", freeChat
                ? (scenario.getAiRole() != null ? scenario.getAiRole().getName() : "Bạn Nhật")
                : (currentSpeaker != null ? currentSpeaker.name() : scenario.getAiRole() != null ? scenario.getAiRole().getName() : "AI"));
        model.addAttribute("userRoleName", freeChat
                ? "Bạn"
                : (scenario.getUserRole() != null ? scenario.getUserRole().getName() : "You"));
        model.addAttribute("categoryIcon", freeChat
                ? "fa-solid fa-comments"
                : (scenario.getCategory() != null && scenario.getCategory().getIcon() != null
                        ? scenario.getCategory().getIcon() : "fa-solid fa-comments"));
        String backgroundUrl = freeChat ? null : SceneImageSupport.current(session, scenarioContent);
        if (!freeChat && (backgroundUrl == null || backgroundUrl.isBlank())
                && scenario.getSlug() != null && !scenario.getSlug().isBlank()) {
            backgroundUrl = "/images/scenarios/" + scenario.getSlug() + ".webp";
        }
        model.addAttribute("scenarioBackgroundUrl", freeChat ? "" : backgroundUrl);
        return "aicall/conversation";
    }

    @GetMapping("/conversation/{sessionId}/result")
    public String result(@PathVariable Long sessionId, @AuthenticationPrincipal UserPrincipal principal, Model model) {
        ConversationSession session = ownedSession(sessionId, principal);
        if (session == null) {
            return "redirect:/ai-call";
        }
        SessionScore score = scoreRepository.findBySessionId(sessionId).orElse(null);
        model.addAttribute("chatSession", session);
        model.addAttribute("scenario", session.getScenario());
        model.addAttribute("score", score);
        model.addAttribute("scoreDetails", scoreDetails(score));
        model.addAttribute("feedbacks", feedbackRepository.findBySessionIdOrderByCreatedAtAsc(sessionId));
        model.addAttribute("stageDefinition",StageFlowSupport.stage(session));
        return "aicall/result";
    }

    /** Điểm theo từng tiêu chí (ngữ pháp, từ vựng, ...) từ score_details_json, giữ đúng thứ tự đã chấm. */
    private Map<String, Integer> scoreDetails(SessionScore score) {
        Map<String, Integer> details = new java.util.LinkedHashMap<>();
        if (score == null || score.getDetailsJson() == null) {
            return details;
        }
        try {
            objectMapper.readTree(score.getDetailsJson()).properties()
                    .forEach(entry -> details.put(entry.getKey(), (int) Math.round(entry.getValue().asDouble())));
        } catch (Exception ignored) {
            // JSON hỏng thì không hiện phần chi tiết, điểm tổng vẫn hiện.
        }
        return details;
    }

    /** Chỉ chủ phiên mới xem được; phiên của người khác coi như không tồn tại. */
    private ConversationSession ownedSession(Long sessionId, UserPrincipal principal) {
        return sessionRepository.findDetail(sessionId)
                .filter(session -> principal != null && principal.userId().equals(session.getUserId()))
                .orElse(null);
    }
}
