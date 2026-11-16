package com.jlas.tutor;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.kaiwa.rag.RagPromptComposer;
import com.jlas.kaiwa.rag.RagProperties;
import com.jlas.kaiwa.rag.RagTutorService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

/**
 * Chatbot gia sư cho người học: trả lời câu hỏi tiếng Nhật bằng nội dung giáo trình J-LAS có trích nguồn.
 * Tách khỏi AI Call. Không lưu cuộc trò chuyện (lịch sử nằm ở trình duyệt) và không ghi nội dung câu hỏi vào log.
 */
@Service
@EnableConfigurationProperties(TutorProperties.class)
public class TutorChatService {

    private static final Logger log = LoggerFactory.getLogger(TutorChatService.class);
    private static final int MAX_HISTORY_CHARS = 1_000;
    private static final Duration WINDOW = Duration.ofHours(1);

    private final RagTutorService tutor;
    private final RagProperties rag;
    private final TutorProperties limits;
    private final Clock clock;
    private final Semaphore slots;
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();
    private final Map<Long, Deque<Instant>> recent = new ConcurrentHashMap<>();

    @Autowired
    public TutorChatService(RagTutorService tutor, RagProperties rag, TutorProperties limits) {
        this(tutor, rag, limits, Clock.systemUTC());
    }

    TutorChatService(RagTutorService tutor, RagProperties rag, TutorProperties limits, Clock clock) {
        this.tutor = tutor;
        this.rag = rag;
        this.limits = limits;
        this.clock = clock;
        this.slots = new Semaphore(limits.maxConcurrent(), true);
    }

    public record Message(String role, String content) { }

    public record Reply(String answer, List<RagPromptComposer.Citation> sources, long retrievalMs, long generationMs) { }

    public record Status(boolean enabled, int maxQuestionChars) { }

    public Status status() {
        return new Status(rag.enabled(), limits.maxQuestionChars());
    }

    public Reply ask(long userId, String question, List<Message> history) {
        if (!rag.enabled()) throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE, "Gia sư AI chưa được bật trên máy chủ.");
        String q = question == null ? "" : question.strip();
        if (q.isEmpty() || q.length() > limits.maxQuestionChars()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Câu hỏi phải có từ 1 đến " + limits.maxQuestionChars() + " ký tự.");
        }
        String smallTalk = SmallTalk.reply(q);
        if (smallTalk != null) return new Reply(smallTalk, List.of(), 0, 0);
        if (!inFlight.add(userId)) {
            throw new ApiException(ErrorCode.TOO_MANY_REQUESTS, "Gia sư đang trả lời câu trước của bạn, chờ một chút nhé.");
        }
        try {
            if (!acquireSlot()) {
                throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE, "Gia sư đang bận trả lời nhiều người, bạn thử lại sau ít giây nhé.");
            }
            try {
                takeHourlyQuota(userId);
                RagTutorService.Answer answer = tutor.ask(q, sanitize(history));
                log.info("Tutor answered userId={} sources={} retrievalMs={} generationMs={}",
                        userId, answer.citations().size(), answer.retrievalMs(), answer.generationMs());
                return new Reply(answer.answer(), cited(answer.answer(), answer.citations()), answer.retrievalMs(), answer.generationMs());
            } finally {
                slots.release();
            }
        } finally {
            inFlight.remove(userId);
        }
    }

    /** Chỉ đưa ra nguồn mà câu trả lời có trích, để câu "chưa có trong tài liệu" không kèm nguồn chẳng liên quan. */
    static List<RagPromptComposer.Citation> cited(String answer, List<RagPromptComposer.Citation> citations) {
        String text = answer == null ? "" : answer;
        return citations.stream().filter(c -> text.contains("[" + c.code() + "]")).toList();
    }

    private boolean acquireSlot() {
        try {
            return slots.tryAcquire(limits.waitSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void takeHourlyQuota(long userId) {
        Instant now = clock.instant();
        Deque<Instant> times = recent.computeIfAbsent(userId, id -> new ArrayDeque<>());
        synchronized (times) {
            while (!times.isEmpty() && times.peekFirst().isBefore(now.minus(WINDOW))) times.pollFirst();
            if (times.size() >= limits.questionsPerHour()) {
                throw new ApiException(ErrorCode.TOO_MANY_REQUESTS,
                        "Bạn đã hỏi " + limits.questionsPerHour() + " câu trong một giờ qua, nghỉ một chút rồi hỏi tiếp nhé.");
            }
            times.addLast(now);
        }
    }

    /** Chỉ giữ vài lượt cuối, đúng vai user/assistant, cắt bớt lượt quá dài. Lịch sử do trình duyệt gửi nên không tin cậy. */
    List<Map<String, String>> sanitize(List<Message> history) {
        if (history == null || history.isEmpty() || limits.historyMessages() == 0) return List.of();
        List<Map<String, String>> kept = new ArrayList<>();
        for (Message message : history) {
            if (message == null || message.content() == null || message.content().isBlank()) continue;
            String role = message.role();
            if (!"user".equals(role) && !"assistant".equals(role)) continue;
            String content = message.content().strip();
            if (content.length() > MAX_HISTORY_CHARS) content = content.substring(0, MAX_HISTORY_CHARS);
            kept.add(Map.of("role", role, "content", content));
        }
        return kept.subList(Math.max(0, kept.size() - limits.historyMessages()), kept.size());
    }
}
