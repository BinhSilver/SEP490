package com.jlas.conjugation;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.auth.security.UserPrincipal;
import com.jlas.common.exception.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/verb-practice") @RequiredArgsConstructor
public class VerbPracticeController {
    private final VerbPracticeService service;
    public record Submission(String questionId,String answer,boolean skip) {}
    private static Long id(UserPrincipal u){if(u==null)throw new ApiException(ErrorCode.UNAUTHORIZED);return u.userId();}
    @GetMapping public ResponseEntity<ApiResponse<VerbPracticeService.Dashboard>> dashboard(@AuthenticationPrincipal UserPrincipal u){return ResponseEntity.ok(ApiResponse.ok("OK",service.dashboard(id(u))));}
    @PostMapping("/sessions") public ResponseEntity<ApiResponse<VerbPracticeService.SessionView>> start(@AuthenticationPrincipal UserPrincipal u,@RequestBody VerbPracticeService.Settings settings){return ResponseEntity.ok(ApiResponse.ok("OK",service.start(id(u),settings)));}
    @GetMapping("/sessions/{session}") public ResponseEntity<ApiResponse<VerbPracticeService.SessionView>> get(@AuthenticationPrincipal UserPrincipal u,@PathVariable String session){return ResponseEntity.ok(ApiResponse.ok("OK",service.get(id(u),session)));}
    @PostMapping("/sessions/{session}/answer") public ResponseEntity<ApiResponse<VerbPracticeService.SessionView>> answer(@AuthenticationPrincipal UserPrincipal u,@PathVariable String session,@RequestBody Submission request){return ResponseEntity.ok(ApiResponse.ok("OK",service.answer(id(u),session,request.questionId(),request.answer(),request.skip())));}
    @PostMapping("/sessions/{session}/finish") public ResponseEntity<ApiResponse<VerbPracticeService.SessionView>> finish(@AuthenticationPrincipal UserPrincipal u,@PathVariable String session){return ResponseEntity.ok(ApiResponse.ok("OK",service.finish(id(u),session)));}
}

@Controller
class VerbPracticePageController {
    @GetMapping("/practice/verbs") public String page(){return "verb-practice";}
}
