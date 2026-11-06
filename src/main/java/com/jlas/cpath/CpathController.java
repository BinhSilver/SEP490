package com.jlas.cpath;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.auth.security.UserPrincipal;
import com.jlas.jlptpath.dto.JpathSpeakResult;
import com.jlas.jlptpath.service.impl.JpathSpeakService;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
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
 * contract the pages read is in the phase-2 plan; speaking reuses the JLPT path's Azure scoring (30 a day).
 */
@RestController
@RequestMapping("/api/learning/comm")
@ConditionalOnProperty(name = "learning.comm-path.enabled", havingValue = "true")
public class CpathController {

    private final CpathService service;
    private final JpathSpeakService speak;

    public CpathController(CpathService service, JpathSpeakService speak) {
        this.service = service;
        this.speak = speak;
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

    @PostMapping(value = "/speak", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<Map<String, Object>>> speak(@AuthenticationPrincipal UserPrincipal user,
            @RequestParam("text") String text, @RequestPart("audio") MultipartFile audio) {
        JpathSpeakResult r = speak.score(user.userId(), text, audio);
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
        return ResponseEntity.ok(ApiResponse.ok("OK", service.day(user.userId(), d == null ? LocalDate.now() : d)));
    }

    @GetMapping("/goal-test")
    public ResponseEntity<ApiResponse<Map<String, Object>>> goalTest(@AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(ApiResponse.ok("OK", service.goalTest(user.userId())));
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

    @GetMapping("/notebook")
    public ResponseEntity<ApiResponse<Map<String, Object>>> notebook(@AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(ApiResponse.ok("OK", service.notebook(user.userId())));
    }

    @PostMapping("/weekly")
    public ResponseEntity<ApiResponse<Map<String, Object>>> submitWeekly(@AuthenticationPrincipal UserPrincipal user,
            @RequestBody(required = false) Map<String, Object> body) {
        return ResponseEntity.ok(ApiResponse.ok("OK", service.submitWeekly(user.userId(), body)));
    }
}
