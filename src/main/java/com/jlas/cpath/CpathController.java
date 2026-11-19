package com.jlas.cpath;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.auth.security.UserPrincipal;
import com.jlas.auth.service.PremiumAccess;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.common.security.ContentReviewAccess;
import jakarta.servlet.http.HttpServletRequest;
import com.jlas.jlptpath.dto.JpathSpeakResult;
import com.jlas.jlptpath.service.impl.JpathSpeakService;
import com.jlas.kaiwa.service.quota.AiCallQuotaService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * [JLAS-W0108] API of the phase-2 communication path (owner 06/11), behind {@code learning.comm-path.enabled}. The
 * contract the pages read is in the phase-2 plan; speaking reuses the JLPT path's pronunciation scoring (30 a day).
 */
@RestController
@RequestMapping("/api/learning/comm")
@ConditionalOnProperty(name = "learning.comm-path.enabled", havingValue = "true")
public class CpathController {

    private final CpathService service;
    private final JpathSpeakService speak;
    private final CpathRoleplayService roleplay;
    private final AiCallQuotaService quota;
    private final CpathCoreService core;
    private final CpathTravelService travel;
    private static final ObjectMapper JSON = new ObjectMapper();

    public CpathController(CpathService service, JpathSpeakService speak, CpathRoleplayService roleplay,
                           AiCallQuotaService quota, CpathCoreService core, CpathTravelService travel) {
        this.service = service;
        this.core = core;
        this.travel = travel;
        this.speak = speak;
        this.roleplay = roleplay;
        this.quota = quota;
    }

    @Autowired(required = false)
    private PremiumAccess premium;

    /** Owner 11/11: the whole communication path is Premium; survey, path page, map and settings stay open. */
    private static final List<String> FREE_PATHS = List.of("/goals", "/path", "/map", "/premium-gate", "/path/daily", "/path/pace",
            "/path/display", "/path/pause", "/path/restart", "/path/depth", "/path/promote-secondary", "/core",
            "/notebook", "/day");

    private boolean locked(Long userId) {
        if (premium == null || userId == null || ContentReviewAccess.allowed(userId)) return false;
        return !premium.isActive(userId);
    }

    @ModelAttribute
    void premiumOnly(HttpServletRequest request, @AuthenticationPrincipal UserPrincipal user) {
        String uri = request.getRequestURI();
        int at = uri.indexOf("/api/learning/comm");
        String sub = at < 0 ? uri : uri.substring(at + "/api/learning/comm".length());
        if (FREE_PATHS.contains(sub)) return;
        if (user != null && locked(user.userId())) throw new ApiException(ErrorCode.CPATH_PREMIUM_REQUIRED);
    }

    @GetMapping("/premium-gate")
    public ResponseEntity<ApiResponse<Map<String, Object>>> premiumGate(@AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(ApiResponse.ok("OK", Map.of("locked", user != null && locked(user.userId()))));
    }

    @GetMapping("/goals")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> goals() {
        return ResponseEntity.ok(ApiResponse.ok("OK", service.goals()));
    }

    @PostMapping("/path")
    public ResponseEntity<ApiResponse<Map<String, Object>>> create(@AuthenticationPrincipal UserPrincipal user,
            @RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(ApiResponse.ok("Đã tạo lộ trình giao tiếp.", service.createPath(user.userId(), body)));
    }

    @GetMapping("/path")
    public ResponseEntity<ApiResponse<Map<String, Object>>> path(@AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(ApiResponse.ok("OK", service.path(user.userId())));
    }

    @GetMapping("/map")
    public ResponseEntity<ApiResponse<Map<String, Object>>> map(@AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(ApiResponse.ok("OK", service.map(user.userId())));
    }

    @PostMapping("/today/extra")
    public ResponseEntity<ApiResponse<Map<String, Object>>> extra(@AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(ApiResponse.ok("OK", service.learnExtra(user.userId())));
    }

    @GetMapping("/situations/{id}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> situation(@AuthenticationPrincipal UserPrincipal user,
            @PathVariable String id) {
        return ResponseEntity.ok(ApiResponse.ok("OK", service.situation(user.userId(), id)));
    }

    @PostMapping("/situations/{id}/steps/{step}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> step(@AuthenticationPrincipal UserPrincipal user,
            @PathVariable String id, @PathVariable String step, @RequestBody(required = false) Map<String, Object> body) {
        return ResponseEntity.ok(ApiResponse.ok("OK", service.step(user.userId(), id, step, body)));
    }

    /** F64: "Tôi biết tình huống này" — questions without answers. */
    @GetMapping("/situations/{id}/known-check")
    public ResponseEntity<ApiResponse<Map<String, Object>>> knownCheck(@AuthenticationPrincipal UserPrincipal user,
            @PathVariable String id) {
        return ResponseEntity.ok(ApiResponse.ok("OK", service.knownCheck(user.userId(), id)));
    }

    /** F64: answers of the known-check; >= 90% counts the situation as learned. */
    @PostMapping("/situations/{id}/known-check")
    public ResponseEntity<ApiResponse<Map<String, Object>>> submitKnownCheck(@AuthenticationPrincipal UserPrincipal user,
            @PathVariable String id, @RequestBody(required = false) Map<String, Object> body) {
        return ResponseEntity.ok(ApiResponse.ok("OK", service.submitKnownCheck(user.userId(), id, body)));
    }

    @PostMapping(value = "/speak", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<Map<String, Object>>> speak(@AuthenticationPrincipal UserPrincipal user,
            @RequestParam("text") String text, @RequestPart("audio") MultipartFile audio,
            @RequestParam(name = "lineId", required = false) String lineId) {
        JpathSpeakResult r = speak.score(user.userId(), text, audio);
        if (lineId != null && !lineId.isBlank() && r.pronunciation() != null)
            service.recordSpeechScore(user.userId(), lineId, text, (int) Math.round(r.pronunciation()));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("score", r.pronunciation() == null ? null : Math.round(r.pronunciation()));
        m.put("accuracy", r.accuracy());
        m.put("fluency", r.fluency());
        m.put("completeness", r.completeness());
        m.put("recognizedText", r.recognizedText());
        m.put("feedbackVi", r.feedbackVi());
        m.put("words", r.words() == null ? List.of() : r.words().stream()
                .map(w -> Map.<String, Object>of("word", w.word(), "score", w.accuracy() == null ? 0 : Math.round(w.accuracy()))).toList());
        m.put("attemptsLeft", r.attemptsLeft());
        return ResponseEntity.ok(ApiResponse.ok("OK", m));
    }

    @GetMapping("/day")
    public ResponseEntity<ApiResponse<Map<String, Object>>> day(@AuthenticationPrincipal UserPrincipal user,
            @RequestParam(name = "d", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate d) {
        return ResponseEntity.ok(ApiResponse.ok("OK", service.day(user.userId(), d)));
    }

    @GetMapping("/goal-test")
    public ResponseEntity<ApiResponse<Map<String, Object>>> goalTest(@AuthenticationPrincipal UserPrincipal user) {
        Map<String, Object> result = service.goalTest(user.userId());
        AiCallQuotaService.UsageSnapshot usage = quota.snapshot(user);
        boolean available = result.get("scenarioUrl") != null && (usage.unlimited()
                || (usage.dailyMinutes() < usage.dailyLimitMin() && usage.monthlyMinutes() < usage.monthlyLimitMin()));
        result.put("aiAvailable", available);
        result.put("fallbackUrl", "/learning/comm/weekly?mode=fallback");
        return ResponseEntity.ok(ApiResponse.ok("OK", result));
    }

    @GetMapping("/goal-test/fallback")
    public ResponseEntity<ApiResponse<Map<String, Object>>> fallback(@AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(ApiResponse.ok("OK", roleplay.get(user.userId(), "goal", null)));
    }

    @PostMapping(value = "/goal-test/fallback", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<Map<String, Object>>> submitFallback(@AuthenticationPrincipal UserPrincipal user,
            @RequestParam("answers") String answers, @RequestPart(name = "audio", required = false) List<MultipartFile> audio) {
        return ResponseEntity.ok(ApiResponse.ok("OK", gradeRoleplay(user, "goal", null, answers, audio)));
    }

    @GetMapping("/roleplay")
    public ResponseEntity<ApiResponse<Map<String, Object>>> roleplay(@AuthenticationPrincipal UserPrincipal user,
            @RequestParam String scope, @RequestParam(required = false) String code) {
        return ResponseEntity.ok(ApiResponse.ok("OK", roleplay.get(user.userId(), scope, code)));
    }

    @PostMapping("/roleplay/pick")
    public ResponseEntity<ApiResponse<Map<String, Object>>> quickRoleplayPick(
            @AuthenticationPrincipal UserPrincipal user, @RequestParam String code,
            @RequestParam String questionId, @RequestParam int optionIndex) {
        return ResponseEntity.ok(ApiResponse.ok("OK", roleplay.quickPick(user.userId(), code, questionId, optionIndex)));
    }

    @PostMapping(value = "/roleplay", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<Map<String, Object>>> submitRoleplay(@AuthenticationPrincipal UserPrincipal user,
            @RequestParam String scope, @RequestParam(required = false) String code,
            @RequestParam("answers") String answers, @RequestPart(name = "audio", required = false) List<MultipartFile> audio) {
        return ResponseEntity.ok(ApiResponse.ok("OK", gradeRoleplay(user, scope, code, answers, audio)));
    }

    private Map<String, Object> gradeRoleplay(UserPrincipal user, String scope, String code,
                                                String answersJson, List<MultipartFile> audio) {
        Map<String, Integer> answers;
        try {
            answers = JSON.readValue(answersJson, new TypeReference<>() {});
        } catch (Exception ex) {
            throw new com.jlas.common.exception.ApiException(com.jlas.common.exception.ErrorCode.VALIDATION_FAILED,
                    "Đáp án không hợp lệ.");
        }
        List<CpathRoleplayService.Question> questions = roleplay.gradingQuestions(user.userId(), scope, code);
        List<CpathRoleplayService.Question> spoken = questions.stream().filter(CpathRoleplayService.Question::speaking).toList();
        List<MultipartFile> clips = audio == null ? List.of() : audio;
        if (!clips.isEmpty() && clips.size() != spoken.size())
            throw new com.jlas.common.exception.ApiException(com.jlas.common.exception.ErrorCode.VALIDATION_FAILED,
                    "Thiếu bản ghi âm của câu nói.");
        Map<String, Integer> scores = new LinkedHashMap<>();
        try {
            for (int i = 0; i < clips.size(); i++) {
                JpathSpeakResult result = speak.score(user.userId(), spoken.get(i).replyJa(), clips.get(i));
                if (result.pronunciation() == null) throw new IllegalStateException("Speech score unavailable");
                scores.put(spoken.get(i).id(), (int) Math.round(result.pronunciation()));
            }
        } catch (RuntimeException scoringFailure) {
            scores.clear();
        }
        return roleplay.submit(user.userId(), scope, code, answers, scores);
    }

    @PostMapping("/goal-test/check")
    public ResponseEntity<ApiResponse<Map<String, Object>>> checkGoalTest(@AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(ApiResponse.ok("OK", service.checkGoalTest(user.userId())));
    }

    /** Owner 06/11: the weekly test of the communication path (questions without answers). */
    @GetMapping("/weekly")
    public ResponseEntity<ApiResponse<Map<String, Object>>> weekly(@AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(ApiResponse.ok("OK", service.weekly(user.userId())));
    }

    @PostMapping(value = "/weekly/speak", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<Map<String, Object>>> weeklySpeak(@AuthenticationPrincipal UserPrincipal user,
            @RequestPart(name = "audio", required = false) List<MultipartFile> audio) {
        return ResponseEntity.ok(ApiResponse.ok("OK", scoreSpeechPractice(user, audio, true)));
    }

    @PostMapping(value = "/review/speak", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<Map<String, Object>>> reviewSpeak(@AuthenticationPrincipal UserPrincipal user,
            @RequestPart(name = "audio", required = false) List<MultipartFile> audio) {
        return ResponseEntity.ok(ApiResponse.ok("OK", scoreSpeechPractice(user, audio, false)));
    }

    private Map<String, Object> scoreSpeechPractice(UserPrincipal user, List<MultipartFile> audio, boolean weekly) {
        List<Map<String, Object>> questions = weekly
                ? service.weeklySpeakingQuestions(user.userId()) : service.reviewSpeakingQuestions(user.userId());
        List<MultipartFile> clips = audio == null ? List.of() : audio;
        if (clips.size() != questions.size())
            throw new com.jlas.common.exception.ApiException(com.jlas.common.exception.ErrorCode.VALIDATION_FAILED,
                    "Thiếu bản ghi âm của câu nói.");
        Map<String, Integer> scores = new LinkedHashMap<>();
        for (int i = 0; i < clips.size(); i++) {
            Map<String, Object> question = questions.get(i);
            JpathSpeakResult result = speak.score(user.userId(), String.valueOf(question.get("ja")), clips.get(i));
            scores.put(String.valueOf(question.get("id")), result.pronunciation() == null ? 0 : (int) Math.round(result.pronunciation()));
        }
        return weekly ? service.recordWeeklySpeech(user.userId(), scores) : service.recordReviewSpeech(user.userId(), scores);
    }

    /** Owner 06/11: today's spaced review of words, phrases and Sổ tay entries (no CP, not in progress). */
    @GetMapping("/review")
    public ResponseEntity<ApiResponse<Map<String, Object>>> review(@AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(ApiResponse.ok("OK", service.review(user.userId())));
    }

    @PostMapping("/review")
    public ResponseEntity<ApiResponse<Map<String, Object>>> submitReview(@AuthenticationPrincipal UserPrincipal user,
            @RequestBody(required = false) Map<String, Object> body) {
        return ResponseEntity.ok(ApiResponse.ok("OK", service.submitReview(user.userId(), body)));
    }

    @PostMapping("/review/pick")
    public ResponseEntity<ApiResponse<Map<String, Object>>> reviewPick(@AuthenticationPrincipal UserPrincipal user,
            @RequestParam String questionId, @RequestParam int optionIndex) {
        return ResponseEntity.ok(ApiResponse.ok("OK", service.reviewPick(user.userId(), questionId, optionIndex)));
    }

    /** The slider: 1 or 2 situations a day. */
    @org.springframework.web.bind.annotation.PutMapping("/path/daily")
    public ResponseEntity<ApiResponse<Map<String, Object>>> setDaily(@AuthenticationPrincipal UserPrincipal user,
            @RequestBody(required = false) Map<String, Object> body) {
        return ResponseEntity.ok(ApiResponse.ok("Đã đổi số tình huống mỗi ngày.", service.setDaily(user.userId(), body)));
    }

    @org.springframework.web.bind.annotation.PutMapping("/path/pace")
    public ResponseEntity<ApiResponse<Map<String, Object>>> setPace(@AuthenticationPrincipal UserPrincipal user,
            @RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(ApiResponse.ok("Đã lưu nhịp học.", service.setPace(user.userId(), body)));
    }

    /** F61: furigana / romaji / translation saved for the main path. */
    @org.springframework.web.bind.annotation.PutMapping("/path/display")
    public ResponseEntity<ApiResponse<Map<String, Object>>> setDisplay(@AuthenticationPrincipal UserPrincipal user,
            @RequestBody(required = false) Map<String, Object> body) {
        return ResponseEntity.ok(ApiResponse.ok("Đã lưu cách hiển thị.", service.setDisplay(user.userId(), body)));
    }

    /** F61: pause the main path, progress kept. */
    @PostMapping("/path/pause")
    public ResponseEntity<ApiResponse<Map<String, Object>>> pausePath(@AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(ApiResponse.ok("Đã tạm dừng lộ trình Giao tiếp.", service.pausePath(user.userId())));
    }

    /** F125: "Nâng mục tiêu phụ lên làm chính" from the finished-path card (keeps the secondary's progress). */
    @PostMapping("/path/promote-secondary")
    public ResponseEntity<ApiResponse<Map<String, Object>>> promoteSecondary(@AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(ApiResponse.ok("Đã nâng mục tiêu phụ lên làm lộ trình chính.", service.promoteSecondary(user.userId())));
    }

    /** F61: clear the main path's progress after typing BẮT ĐẦU LẠI. */
    @PostMapping("/path/restart")
    public ResponseEntity<ApiResponse<Map<String, Object>>> restartPath(@AuthenticationPrincipal UserPrincipal user,
            @RequestBody(required = false) Map<String, Object> body) {
        return ResponseEntity.ok(ApiResponse.ok("Đã đặt lại lộ trình Giao tiếp.", service.restartPath(user.userId(), body)));
    }

    @GetMapping("/notebook")
    public ResponseEntity<ApiResponse<Map<String, Object>>> notebook(@AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(ApiResponse.ok("OK", service.notebook(user.userId())));
    }

    /** F196: save / unsave a word or phrase card in the notebook. */
    @PostMapping("/notebook/{code}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> saveNote(@AuthenticationPrincipal UserPrincipal user,
            @PathVariable String code) {
        return ResponseEntity.ok(ApiResponse.ok("Đã lưu vào sổ tay.", service.saveNote(user.userId(), code, true)));
    }

    @DeleteMapping("/notebook/{code}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> unsaveNote(@AuthenticationPrincipal UserPrincipal user,
            @PathVariable String code) {
        return ResponseEntity.ok(ApiResponse.ok("Đã bỏ khỏi sổ tay.", service.saveNote(user.userId(), code, false)));
    }

    @PostMapping("/weekly")
    public ResponseEntity<ApiResponse<Map<String, Object>>> submitWeekly(@AuthenticationPrincipal UserPrincipal user,
            @RequestBody(required = false) Map<String, Object> body) {
        return ResponseEntity.ok(ApiResponse.ok("OK", service.submitWeekly(user.userId(), body)));
    }

    // ------------------------------------------------------------------------------------- F70 base set

    /** The base-set block of the path page and Settings (enabled false when the goal or the DB has none). */
    @GetMapping("/core")
    public ResponseEntity<ApiResponse<Map<String, Object>>> core(@AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(ApiResponse.ok("OK", core.overview(user.userId())));
    }

    @GetMapping("/core/lesson")
    public ResponseEntity<ApiResponse<Map<String, Object>>> coreLesson(@AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(ApiResponse.ok("OK", core.lesson(user.userId())));
    }

    @PostMapping("/core/lesson")
    public ResponseEntity<ApiResponse<Map<String, Object>>> submitCoreLesson(@AuthenticationPrincipal UserPrincipal user,
            @RequestBody(required = false) Map<String, Object> body) {
        return ResponseEntity.ok(ApiResponse.ok("OK", core.submitLesson(user.userId(), body)));
    }

    @GetMapping("/core/check")
    public ResponseEntity<ApiResponse<Map<String, Object>>> coreCheck(@AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(ApiResponse.ok("OK", core.knownCheck(user.userId())));
    }

    @PostMapping("/core/check")
    public ResponseEntity<ApiResponse<Map<String, Object>>> submitCoreCheck(@AuthenticationPrincipal UserPrincipal user,
            @RequestBody(required = false) Map<String, Object> body) {
        return ResponseEntity.ok(ApiResponse.ok("OK", core.submitKnownCheck(user.userId(), body)));
    }

    @org.springframework.web.bind.annotation.PutMapping("/path/depth")
    public ResponseEntity<ApiResponse<Map<String, Object>>> setDepth(@AuthenticationPrincipal UserPrincipal user,
            @RequestBody(required = false) Map<String, Object> body) {
        return ResponseEntity.ok(ApiResponse.ok("Đã đổi độ sâu.", core.setDepth(user.userId(), body)));
    }

    // ------------------------------------------------------------------------------------- F70 Du lịch extras

    @GetMapping("/travel/phrasebook")
    public ResponseEntity<ApiResponse<Map<String, Object>>> phrasebook(@AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(ApiResponse.ok("OK", travel.phrasebook(user.userId())));
    }

    @GetMapping("/travel/game")
    public ResponseEntity<ApiResponse<Map<String, Object>>> game(@AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(ApiResponse.ok("OK", travel.game(user.userId())));
    }

    @PostMapping("/travel/game")
    public ResponseEntity<ApiResponse<Map<String, Object>>> submitGame(@AuthenticationPrincipal UserPrincipal user,
            @RequestBody(required = false) Map<String, Object> body) {
        return ResponseEntity.ok(ApiResponse.ok("OK", travel.submitGame(user.userId(), body)));
    }

    @GetMapping("/travel/stamps")
    public ResponseEntity<ApiResponse<Map<String, Object>>> stamps(@AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(ApiResponse.ok("OK", travel.stamps(user.userId())));
    }
}
