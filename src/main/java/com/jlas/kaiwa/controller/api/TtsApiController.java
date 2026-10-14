package com.jlas.kaiwa.controller.api;

import com.jlas.auth.security.CurrentUser;
import com.jlas.kaiwa.config.FishAudioConfig;
import com.jlas.kaiwa.service.ai.FishAudioClient;
import com.jlas.auth.security.UserPrincipal;
import com.jlas.moderation.service.UserSanctionService;
import com.jlas.kaiwa.repository.ConversationMessageRepository;
import com.jlas.kaiwa.repository.ConversationSessionRepository;
import com.jlas.kaiwa.service.scenario.ConversationSpeakerService;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/api/tts")
public class TtsApiController {

    private final FishAudioClient fishAudioClient;
    private final FishAudioConfig fishAudioConfig;
    private final UserSanctionService sanctions;
    private final ConversationMessageRepository messages;
    private final ConversationSpeakerService speakers;
    private final ConversationSessionRepository sessions;

    public TtsApiController(FishAudioClient fishAudioClient, FishAudioConfig fishAudioConfig,
            UserSanctionService sanctions, ConversationMessageRepository messages, ConversationSpeakerService speakers,
            ConversationSessionRepository sessions) {
        this.fishAudioClient = fishAudioClient;
        this.fishAudioConfig = fishAudioConfig;
        this.sanctions = sanctions;
        this.messages = messages; this.speakers = speakers;
        this.sessions = sessions;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of(
                "configured", fishAudioConfig.isConfigured(),
                "provider", "fish.audio",
                "model", fishAudioConfig.getModel() != null ? fishAudioConfig.getModel() : "");
    }

    /**
     * Body: { "text": "...", "gender": "female"|"male", "speed": 1.0 }
     * Returns audio/mpeg (mp3).
     */
    @PostMapping(value = "/speak", produces = "audio/mpeg")
    public ResponseEntity<?> speak(@RequestBody Map<String, Object> body) {
        TtsInput input = parseInput(body);
        if (input.error() != null) {
            return input.error();
        }

        try {
            byte[] audio = input.voiceId() == null
                    ? fishAudioClient.synthesize(input.text(), input.gender(), input.speed())
                    : fishAudioClient.synthesizeVoice(input.text(), input.voiceId(), input.speed());
            HttpHeaders headers = audioHeaders();
            headers.setContentLength(audio.length);
            return new ResponseEntity<>(audio, headers, HttpStatus.OK);
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (IllegalStateException e) {
            return badGateway(e.getMessage());
        }
    }

    /**
     * Streams one continuous MP3 response. The browser can begin playback before
     * Fish Audio has finished synthesizing the complete message.
     */
    @PostMapping(value = "/speak-stream", produces = "audio/mpeg")
    public ResponseEntity<?> speakStream(@RequestBody Map<String, Object> body) {
        TtsInput input = parseInput(body);
        if (input.error() != null) {
            return input.error();
        }

        StreamingResponseBody stream = output -> {
            if (input.voiceId() == null) fishAudioClient.synthesizeStreaming(input.text(), input.gender(), input.speed(), output);
            else fishAudioClient.synthesizeVoiceStreaming(input.text(), input.voiceId(), input.speed(), output);
        };
        return new ResponseEntity<>(stream, audioHeaders(), HttpStatus.OK);
    }

    private TtsInput parseInput(Map<String, Object> body) {
        String text = body.get("text") == null ? "" : String.valueOf(body.get("text")).trim();
        if (text.isEmpty()) {
            return new TtsInput("", "female", 1.0, null, badRequest("text is required"));
        }
        // Chặn user mute dùng AI Call
        UserPrincipal current = CurrentUser.current().orElse(null);
        if (current != null) {
            sanctions.assertCanAct(current.userId());
        }
        if (text.length() > 2000) {
            return new TtsInput("", "female", 1.0, null, badRequest("text too long (max 2000 chars)"));
        }

        String gender = body.get("gender") == null ? "female" : String.valueOf(body.get("gender"));
        if (!"male".equalsIgnoreCase(gender) && !"female".equalsIgnoreCase(gender)) {
            gender = "female";
        }

        double speed = 1.0;
        if (body.get("speed") != null) {
            try {
                speed = Double.parseDouble(String.valueOf(body.get("speed")));
            } catch (NumberFormatException ignored) {
                speed = 1.0;
            }
        }

        String voiceId = null;
        if (body.get("messageId") != null) {
            long messageId;
            try { messageId = Long.parseLong(String.valueOf(body.get("messageId"))); }
            catch (NumberFormatException e) { throw new ApiException(ErrorCode.VALIDATION_FAILED, "Mã lời thoại không hợp lệ."); }
            if (current == null) throw new ApiException(ErrorCode.UNAUTHORIZED);
            var message = messages.findOwnedAssistant(messageId, current.userId())
                    .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
            var actor = speakers.forMessage(message);
            if (actor != null) {
                voiceId = actor.voiceId();
                if (actor.voiceVariants() != null && body.get("gender") != null) {
                    voiceId = actor.voiceVariants().getOrDefault(gender.toLowerCase(java.util.Locale.ROOT), voiceId);
                }
                // The client cannot replace a stored speaker's line or submit arbitrary voice IDs.
                text = message.getContent();
            }
        } else if (body.get("speakerCode") != null) {
            if (current == null) throw new ApiException(ErrorCode.UNAUTHORIZED);
            long sessionId;
            try { sessionId = Long.parseLong(String.valueOf(body.get("sessionId"))); }
            catch (NumberFormatException e) { throw new ApiException(ErrorCode.VALIDATION_FAILED); }
            var session = sessions.findDetail(sessionId).filter(s -> current.userId().equals(s.getUserId()))
                    .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
            String code = String.valueOf(body.get("speakerCode"));
            var actor = speakers.cast(session).stream().filter(s -> code.equals(s.code())).findFirst()
                    .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
            voiceId = actor.voiceId();
            if (actor.voiceVariants() != null && body.get("gender") != null) {
                voiceId = actor.voiceVariants().getOrDefault(gender.toLowerCase(java.util.Locale.ROOT), voiceId);
            }
        }
        return new TtsInput(text, gender, speed, voiceId, null);
    }

    private static HttpHeaders audioHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("audio/mpeg"));
        headers.set(HttpHeaders.CACHE_CONTROL, "no-store");
        headers.set("X-Accel-Buffering", "no");
        return headers;
    }

    private static ResponseEntity<?> badRequest(String message) {
        return ResponseEntity.badRequest()
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("error", message));
    }

    private static ResponseEntity<?> badGateway(String message) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("error", message));
    }

    private record TtsInput(String text, String gender, double speed, String voiceId, ResponseEntity<?> error) {
    }
}
