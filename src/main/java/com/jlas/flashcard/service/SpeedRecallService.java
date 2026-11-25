package com.jlas.flashcard.service;

import com.jlas.auth.entity.User;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.flashcard.dto.SpeedRecallDtos.*;
import com.jlas.flashcard.entity.*;
import com.jlas.flashcard.repository.*;
import com.jlas.flashcard.speedrecall.SpeedRecallMode;
import com.jlas.flashcard.speedrecall.SpeedRecallRules;
import com.jlas.flashcard.speedrecall.SpeedRecallRules.*;
import jakarta.persistence.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

@Service @RequiredArgsConstructor @Transactional
public class SpeedRecallService {
    private final FlashcardStudyAccess access;
    private final FlashcardCardRepository cards;
    private final FlashcardCardProgressRepository progress;
    private final FlashcardStudySessionRepository studies;
    private final FlashcardSpeedRecallSessionRepository sessions;
    private final FlashcardSpeedRecallAnswerRepository answers;
    private final EntityManager em;
    private final SpeedRecallClock clock;
    private final SpeedRecallTimeLimitRepository timeLimits;

    public Options options(Long setId, Long userId, String modeName) {
        requireUser(userId);
        SpeedRecallMode mode = mode(modeName);
        FlashcardSet set = access.studyable(setId, userId);
        List<FlashcardCard> all = cards.findBySetIdOrderByPositionAscIdAsc(setId);
        Map<Long, FlashcardCardProgress> states = progress(userId, setId);
        List<FlashcardCard> eligible = select(all, states, "ALL", mode);
        Set<Long> eligibleIds = new HashSet<>(eligible.stream().map(FlashcardCard::getId).toList());
        Set<Long> learningIds = new HashSet<>(select(all, states, "LEARNING", mode).stream().map(FlashcardCard::getId).toList());
        Map<String, Integer> modeCounts = new LinkedHashMap<>();
        for (SpeedRecallMode each : SpeedRecallMode.values()) {
            modeCounts.put(each.name(), select(all, states, "ALL", each).size());
        }
        return new Options(setId, set.getTitle(), all.size(), eligible.size(), all.size() - eligible.size(),
                select(all, states, "LEARNING", mode).size(), select(all, states, "STARRED", mode).size(), mode.name(),
                modeCounts, all.stream().map(card -> new OptionCard(card.getId(), card.getFront(), card.getReading(),
                        card.getMeaning(), card.getHanViet(), eligibleIds.contains(card.getId()), learningIds.contains(card.getId()),
                        eligibleIds.contains(card.getId()) ? "" : mode.eligible(card) ? "Trùng cặp từ" : "Thiếu nội dung hợp lệ cho cặp hỏi–đáp"))
                        .toList());
    }

    public View create(Long setId, Long userId, CreateRequest request) {
        requireUser(userId);
        SpeedRecallMode mode = mode(request.mode());
        Integer seconds = request.timeLimitSeconds();
        if (seconds != null && !SpeedRecallRules.TIME_LIMIT_CHOICES.contains(seconds)) {
            throw bad("Thời gian mỗi từ phải là một trong các mức " + SpeedRecallRules.TIME_LIMIT_CHOICES + " giây.");
        }
        String fingerprint = hash(setId + "|" + request.filter() + "|" + request.count() + "|" + request.autoSubmit()
                + "|" + mode.name() + (request.selectedCardIds() == null ? "" : "|" + new TreeSet<>(request.selectedCardIds()))
                + (seconds == null ? "" : "|time:" + seconds));
        lockUser(userId);
        Optional<FlashcardStudySession> previous = studies.findByUserIdAndCreationRequestId(userId, request.requestId());
        if (previous.isPresent()) return duplicate(previous.get(), fingerprint, setId, userId);
        FlashcardSet set = access.studyable(setId, userId);
        List<FlashcardCard> pool = select(cards.findBySetIdOrderByPositionAscIdAsc(setId), progress(userId, setId), request.filter(), mode);
        pool = requestedCards(pool, request);
        return build(set, userId, pool, request.count(), request.filter(), request.autoSubmit(), null, request.requestId(), fingerprint, mode,
                seconds);
    }

    /** The previewed subset must belong to this set and remain eligible for the requested pair. */
    static List<FlashcardCard> requestedCards(List<FlashcardCard> pool, CreateRequest request) {
        boolean subset = "CUSTOM".equals(request.filter()) || "RANDOM".equals(request.filter());
        if (!subset) {
            if (request.selectedCardIds() != null) throw new ApiException(ErrorCode.BAD_REQUEST, "Chỉ gửi danh sách thẻ ở chế độ chọn tùy ý hoặc ngẫu nhiên.");
            return pool;
        }
        List<Long> ids = request.selectedCardIds();
        if (ids == null || ids.isEmpty() || ids.stream().anyMatch(id -> id == null || id <= 0)
                || new HashSet<>(ids).size() != ids.size() || request.count() != ids.size()) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Số câu phải khớp với danh sách thẻ hợp lệ đã chọn.");
        }
        Set<Long> wanted = new HashSet<>(ids);
        List<FlashcardCard> selected = pool.stream().filter(card -> wanted.contains(card.getId())).toList();
        if (selected.size() != wanted.size()) throw new ApiException(ErrorCode.BAD_REQUEST, "Thẻ đã chọn không thuộc bộ này hoặc không đủ nội dung cho cặp hỏi–đáp.");
        return new ArrayList<>(selected);
    }

    public View retry(Long setId, Long userId, Long id, RetryRequest request) {
        requireUser(userId);
        lockUser(userId);
        String fingerprint = hash(setId + "|retry|" + id);
        Optional<FlashcardStudySession> previous = studies.findByUserIdAndCreationRequestId(userId, request.requestId());
        if (previous.isPresent()) return duplicate(previous.get(), fingerprint, setId, userId);
        FlashcardSpeedRecallSession source = owned(setId, userId, id);
        if (source.getState() != State.FINISHED) throw conflict("Phiên học chưa hoàn tất");
        Set<Long> wrong = new HashSet<>();
        for (FlashcardSpeedRecallAnswer a : rows(id)) {
            if ((a.getOutcome() == Outcome.WRONG || a.getOutcome() == Outcome.TIMEOUT) && a.getCard() != null) wrong.add(a.getCard().getId());
        }
        SpeedRecallMode mode = mode(source.getSession().getMode());
        List<FlashcardCard> pool = select(cards.findBySetIdOrderByPositionAscIdAsc(setId).stream()
                .filter(c -> wrong.contains(c.getId())).toList(), Map.of(), "ALL", mode);
        // Retrying the missed words keeps the time per word of the session they came from.
        return build(source.getSession().getSet(), userId, pool, pool.size(), "RETRY", source.isAutoSubmit(), id, request.requestId(), fingerprint, mode,
                timeLimits.find(id).orElse(null));
    }

    private View duplicate(FlashcardStudySession previous, String fingerprint, Long setId, Long userId) {
        if (!Objects.equals(previous.getCreationFingerprint(), fingerprint)) throw conflict("Mã yêu cầu đã được dùng cho nội dung khác");
        return state(setId, userId, previous.getId());
    }

    private View build(FlashcardSet set, Long userId, List<FlashcardCard> pool, int count, String filter,
                       boolean auto, Long retryOf, String requestId, String fingerprint, SpeedRecallMode mode,
                       Integer timeLimitSeconds) {
        if (pool.isEmpty()) throw bad("Không có thẻ phù hợp với chế độ này. Hãy bổ sung nội dung còn thiếu cho thẻ hoặc đổi bộ lọc.");
        if (count < 1 || count > pool.size()) {
            throw bad("Số câu hỏi phải từ 1 đến " + pool.size() + " với bộ lọc hiện tại.");
        }
        // Legacy mode keeps its old hash, so personal bests from before modes existed still count.
        String contentVersion = hash((mode == SpeedRecallMode.KANJI_TO_READING ? "" : mode.name())
                + pool.stream().sorted(Comparator.comparing(FlashcardCard::getId))
                .map(c -> c.getId() + "\u0000" + c.getFront() + "\u0000" + c.getReading() + "\u0000" + c.getMeaning()
                        + (mode == SpeedRecallMode.KANJI_TO_READING ? "" : "\u0000" + c.getHanViet()))
                .reduce("", (a, c) -> a + "\n" + c));
        Collections.shuffle(pool);
        List<FlashcardCard> selected = pool.subList(0, Math.min(count, pool.size()));
        LocalDateTime now = clock.now();
        FlashcardStudySession base = studies.saveAndFlush(FlashcardStudySession.builder()
                .user(em.getReference(User.class, userId)).set(set).sessionType("SPEED_RECALL")
                .mode(mode.name()).status("IN_PROGRESS")
                .creationRequestId(requestId).creationFingerprint(fingerprint).build());
        FlashcardSpeedRecallSession s = new FlashcardSpeedRecallSession();
        s.setSession(base); s.setTotalQuestions(selected.size()); s.setSelectionFilter(filter); s.setAutoSubmit(auto);
        s.setRetryOfSessionId(retryOf); s.setRulesVersion(SpeedRecallRules.VERSION); s.setContentVersion(contentVersion);
        s.setCreatedAt(now); s.setLastActivityAt(now); s.setExpiresAt(now.plusMinutes(30));
        sessions.saveAndFlush(s);
        timeLimits.save(s.getId(), timeLimitSeconds);
        List<FlashcardSpeedRecallAnswer> rows = new ArrayList<>();
        for (int i = 0; i < selected.size(); i++) {
            FlashcardCard card = selected.get(i);
            FlashcardSpeedRecallAnswer a = new FlashcardSpeedRecallAnswer();
            a.setSession(s); a.setCard(card); a.setQuestionIndex(i);
            a.setQuestionText(mode.question().of(card));
            a.setExpectedAnswer(mode.answer().of(card));
            a.setReading(SpeedRecallRules.normalize(card.getReading())); a.setMeaning(card.getMeaning());
            rows.add(answers.save(a));
        }
        return view(s, rows);
    }

    public View state(Long setId, Long userId, Long id) {
        FlashcardSpeedRecallSession s = owned(setId, userId, id);
        List<FlashcardSpeedRecallAnswer> rows = rows(id);
        reconcile(s, rows);
        return view(s, rows);
    }

    public View next(Long setId, Long userId, Long id, NextRequest request) {
        FlashcardSpeedRecallSession s = owned(setId, userId, id);
        List<FlashcardSpeedRecallAnswer> rows = rows(id);
        reconcile(s, rows);
        if (terminal(s)) return view(s, rows);
        // Replayed next must never issue a new deadline.
        if (request.questionIndex() == s.getCurrentQuestionIndex() && s.getState() != State.READY) return view(s, rows);
        int expected = s.getState() == State.READY ? 0 : s.getCurrentQuestionIndex() + 1;
        if ((s.getState() != State.READY && s.getState() != State.ANSWER_FEEDBACK)
                || request.questionIndex() != expected || expected >= rows.size()) throw conflict("Thứ tự câu hỏi không hợp lệ");
        if (s.getFeedbackUntil() != null && clock.now().isBefore(s.getFeedbackUntil())) return view(s, rows);
        FlashcardSpeedRecallAnswer a = rows.get(expected);
        if (a.getOutcome() != Outcome.PENDING) throw conflict("Câu hỏi đã bắt đầu");
        issue(s, a);
        return view(s, rows);
    }

    private void issue(FlashcardSpeedRecallSession s, FlashcardSpeedRecallAnswer a) {
        LocalDateTime now = clock.now();
        a.setStreakBefore(s.getStreak()); a.setTimeLimitMs(SpeedRecallRules.timeLimit(s.getStreak(), timeLimits.find(s.getId()).orElse(null)));
        a.setIssuedAt(now); a.setDeadlineAt(now.plusNanos(a.getTimeLimitMs() * 1_000_000L)); a.setOutcome(Outcome.ACTIVE);
        s.setCurrentQuestionIndex(a.getQuestionIndex()); s.setState(State.PLAYING); s.setFeedbackUntil(null);
        touch(s);
    }

    public View answer(Long setId, Long userId, Long id, AnswerRequest request, boolean timeoutSignal) {
        FlashcardSpeedRecallSession s = owned(setId, userId, id);
        List<FlashcardSpeedRecallAnswer> rows = rows(id);
        FlashcardSpeedRecallAnswer a = rows.stream().filter(r -> r.getId().equals(request.questionId())).findFirst()
                .orElseThrow(() -> conflict("Câu hỏi không thuộc phiên học"));
        String fingerprint = hash(request.userAnswer() + "|" + timeoutSignal);
        if (a.getAnswerRequestId() != null) {
            if (!a.getAnswerRequestId().equals(request.requestId()) || !a.getRequestFingerprint().equals(fingerprint))
                throw conflict("Câu hỏi đã được chấm");
            reconcile(s, rows);
            return view(s, rows);
        }
        if (terminal(s) || expired(s)) { reconcile(s, rows); return view(s, rows); }
        if (a.getOutcome() == Outcome.TIMEOUT) return view(s, rows);
        if (s.getState() != State.PLAYING || a.getQuestionIndex() != s.getCurrentQuestionIndex() || a.getOutcome() != Outcome.ACTIVE)
            throw conflict("Câu hỏi không ở trạng thái nhận đáp án");
        LocalDateTime now = clock.now();
        boolean late = !now.isBefore(a.getDeadlineAt());
        if (timeoutSignal && !late) return view(s, rows); // Client clock may be ahead.
        SpeedRecallMode mode = mode(s.getSession().getMode());
        String normalized = mode.comparable(request.userAnswer());
        if (normalized.codePointCount(0, normalized.length()) > 500) throw bad("Đáp án sau chuẩn hóa quá dài");
        if (!late && normalized.isBlank()) throw bad("Hãy nhập đáp án trước khi kiểm tra");
        a.setUserAnswer(request.userAnswer()); a.setNormalizedAnswer(normalized);
        if (!late && !mode.matches(request.userAnswer(), a.getExpectedAnswer())) {
            // A wrong Enter is an attempt, not a completed question. Keep its original deadline.
            touch(s);
            int elapsed = (int) Math.max(0, Math.min(a.getTimeLimitMs(),
                    a.getTimeLimitMs() - Duration.between(now, a.getDeadlineAt()).toMillis()));
            Answer attempt = new Answer(a.getId(), a.getCard() == null ? null : a.getCard().getId(),
                    a.getQuestionText(), a.getReading(), a.getMeaning(), request.userAnswer(),
                    Outcome.WRONG.name(), elapsed, 0, a.getExpectedAnswer());
            return view(s, rows, attempt);
        }
        a.setAnswerRequestId(request.requestId()); a.setRequestFingerprint(fingerprint);
        resolve(s, a, late ? Outcome.TIMEOUT : Outcome.CORRECT);
        // Correct answers issue the next question in the same transaction, without another Enter.
        if (!late && !terminal(s)) issue(s, rows.get(s.getCurrentQuestionIndex() + 1));
        return view(s, rows);
    }

    public View pause(Long setId, Long userId, Long id) {
        FlashcardSpeedRecallSession s = owned(setId, userId, id);
        List<FlashcardSpeedRecallAnswer> rows = rows(id);
        reconcile(s, rows);
        if (terminal(s) || s.getState() == State.PAUSED || s.getState() == State.READY) return view(s, rows);
        LocalDateTime deadline = s.getState() == State.PLAYING ? rows.get(s.getCurrentQuestionIndex()).getDeadlineAt() : s.getFeedbackUntil();
        s.setPausedRemainingMs(Math.max(0, Duration.between(clock.now(), deadline).toMillis()));
        s.setPausedFrom(s.getState()); s.setState(State.PAUSED); touch(s);
        return view(s, rows);
    }

    public View resume(Long setId, Long userId, Long id) {
        FlashcardSpeedRecallSession s = owned(setId, userId, id);
        List<FlashcardSpeedRecallAnswer> rows = rows(id);
        reconcile(s, rows);
        if (s.getState() == State.PAUSED) {
            LocalDateTime deadline = clock.now().plusNanos(s.getPausedRemainingMs() * 1_000_000L);
            if (s.getPausedFrom() == State.PLAYING) rows.get(s.getCurrentQuestionIndex()).setDeadlineAt(deadline);
            else s.setFeedbackUntil(deadline);
            s.setState(s.getPausedFrom()); s.setPausedFrom(null); s.setPausedRemainingMs(null); touch(s);
        }
        return view(s, rows);
    }

    public View abandon(Long setId, Long userId, Long id) {
        FlashcardSpeedRecallSession s = owned(setId, userId, id);
        List<FlashcardSpeedRecallAnswer> rows = rows(id);
        reconcile(s, rows);
        if (!terminal(s)) end(s, State.ABANDONED);
        return view(s, rows);
    }

    public History history(Long setId, Long userId, int page, int size) {
        requireUser(userId); access.studyable(setId, userId);
        var found = sessions.findBySessionUserIdAndSessionSetIdOrderByCreatedAtDesc(userId, setId,
                PageRequest.of(Math.max(0, page), Math.max(1, Math.min(size, 100))));
        return new History(found.stream().map(s -> new HistoryItem(s.getId(), s.getState().name(), instant(s.getCreatedAt()),
                s.getSelectionFilter(), s.isAutoSubmit(), s.getContentVersion(), s.getRulesVersion(), stats(s),
                mode(s.getSession().getMode()).name())).toList(),
                found.getNumber(), found.getTotalPages(), found.getTotalElements());
    }

    public void expire(Long id) {
        sessions.lockById(id).ifPresent(s -> { if (!terminal(s) && expired(s)) end(s, State.EXPIRED); });
    }

    private void reconcile(FlashcardSpeedRecallSession s, List<FlashcardSpeedRecallAnswer> rows) {
        if (terminal(s)) return;
        if (expired(s)) { end(s, State.EXPIRED); return; }
        if (s.getState() == State.PLAYING) {
            FlashcardSpeedRecallAnswer a = rows.get(s.getCurrentQuestionIndex());
            if (!clock.now().isBefore(a.getDeadlineAt())) resolve(s, a, Outcome.TIMEOUT);
        }
    }

    private void resolve(FlashcardSpeedRecallSession s, FlashcardSpeedRecallAnswer a, Outcome outcome) {
        if (a.getOutcome() != Outcome.ACTIVE) return;
        LocalDateTime now = clock.now();
        int elapsed = outcome == Outcome.TIMEOUT ? a.getTimeLimitMs()
                : (int) Math.max(0, Math.min(a.getTimeLimitMs(), a.getTimeLimitMs() - Duration.between(now, a.getDeadlineAt()).toMillis()));
        a.setOutcome(outcome); a.setResponseMs(elapsed); a.setResolvedAt(now);
        s.setTotalResponseMs(s.getTotalResponseMs() + elapsed);
        if (outcome == Outcome.CORRECT) {
            a.setScoreAwarded(SpeedRecallRules.score(elapsed, a.getTimeLimitMs(), a.getStreakBefore()));
            s.setScore(s.getScore() + a.getScoreAwarded()); s.setCorrectCount(s.getCorrectCount() + 1);
            s.setStreak(s.getStreak() + 1); s.setBestStreak(Math.max(s.getBestStreak(), s.getStreak()));
        } else {
            s.setWrongCount(s.getWrongCount() + 1); s.setStreak(0);
            if (outcome == Outcome.TIMEOUT) s.setTimeoutCount(s.getTimeoutCount() + 1);
        }
        s.setFeedbackUntil(now);
        touch(s);
        if (s.getCorrectCount() + s.getWrongCount() == s.getTotalQuestions()) end(s, State.FINISHED);
        else s.setState(State.ANSWER_FEEDBACK);
    }

    private View view(FlashcardSpeedRecallSession s, List<FlashcardSpeedRecallAnswer> rows) {
        return view(s, rows, null);
    }

    private View view(FlashcardSpeedRecallSession s, List<FlashcardSpeedRecallAnswer> rows, Answer attempt) {
        FlashcardSpeedRecallAnswer current = rows.get(s.getCurrentQuestionIndex());
        Question question = s.getState() == State.PLAYING || s.getState() == State.PAUSED && s.getPausedFrom() == State.PLAYING
                ? new Question(current.getId(), current.getQuestionIndex(), current.getQuestionText(), current.getExpectedAnswer(),
                        current.getTimeLimitMs(), instant(current.getDeadlineAt())) : null;
        Answer feedback = attempt != null ? attempt : resolved(current) ? dto(current) : null;
        List<Answer> results = rows.stream().filter(this::resolved).map(this::dto).toList();
        Integer best = null;
        if (s.getState() == State.FINISHED) {
            sessions.flush();
            var bestRows = sessions.best(s.getSession().getUser().getId(), s.getSession().getSet().getId(), s.getRulesVersion(),
                    s.getContentVersion(), s.getSelectionFilter(), s.getTotalQuestions(), s.isAutoSubmit(), PageRequest.of(0, 1));
            if (!bestRows.isEmpty()) best = bestRows.getFirst().getScore();
        }
        return new View(s.getId(), s.getSession().getSet().getId(), s.getSession().getSet().getTitle(), s.getState().name(),
                s.getPausedFrom() == null ? null : s.getPausedFrom().name(), s.getCurrentQuestionIndex(), s.getSelectionFilter(),
                s.isAutoSubmit(), instant(clock.now()), instant(s.getFeedbackUntil()), s.getPausedRemainingMs(),
                question, feedback, stats(s), results, best, mode(s.getSession().getMode()).name());
    }

    private Stats stats(FlashcardSpeedRecallSession s) {
        int resolved = s.getCorrectCount() + s.getWrongCount();
        return new Stats(s.getTotalQuestions(), s.getCorrectCount(), s.getWrongCount(), s.getTimeoutCount(), s.getScore(),
                s.getStreak(), s.getBestStreak(), resolved == 0 ? 0 : Math.round(1000d * s.getCorrectCount() / resolved) / 10d,
                resolved == 0 ? 0 : s.getTotalResponseMs() / resolved);
    }
    private Answer dto(FlashcardSpeedRecallAnswer a) {
        return new Answer(a.getId(), a.getCard() == null ? null : a.getCard().getId(), a.getQuestionText(), a.getReading(),
                a.getMeaning(), a.getUserAnswer(), a.getOutcome().name(), a.getResponseMs(), a.getScoreAwarded(),
                a.getExpectedAnswer());
    }
    private boolean resolved(FlashcardSpeedRecallAnswer a) { return a.getOutcome() != Outcome.PENDING && a.getOutcome() != Outcome.ACTIVE; }
    private boolean terminal(FlashcardSpeedRecallSession s) {
        return s.getState() == State.FINISHED || s.getState() == State.ABANDONED || s.getState() == State.EXPIRED;
    }
    private boolean expired(FlashcardSpeedRecallSession s) { return !clock.now().isBefore(s.getExpiresAt()); }
    private void end(FlashcardSpeedRecallSession s, State state) {
        s.setState(state);
        s.getSession().setStatus(state == State.FINISHED ? "COMPLETED" : "ABANDONED");
        // Base timestamps follow existing application convention; extension clock is UTC.
        s.getSession().setFinishedAt(LocalDateTime.now());
    }
    private void touch(FlashcardSpeedRecallSession s) {
        s.setLastActivityAt(clock.now());
        LocalDateTime max = s.getCreatedAt().plusHours(24), next = clock.now().plusMinutes(30);
        s.setExpiresAt(next.isBefore(max) ? next : max);
    }
    private FlashcardSpeedRecallSession owned(Long setId, Long userId, Long id) {
        requireUser(userId);
        FlashcardSpeedRecallSession s = sessions.lockById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy phiên học"));
        if (!s.getSession().getUser().getId().equals(userId) || !s.getSession().getSet().getId().equals(setId))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Bạn không có quyền truy cập phiên học");
        access.studyable(setId, userId);
        return s;
    }
    private List<FlashcardSpeedRecallAnswer> rows(Long id) { return answers.findBySessionIdOrderByQuestionIndexAsc(id); }
    private Map<Long, FlashcardCardProgress> progress(Long userId, Long setId) {
        Map<Long, FlashcardCardProgress> map = new HashMap<>();
        progress.findByUserIdAndCardSetId(userId, setId).forEach(p -> map.put(p.getCard().getId(), p));
        return map;
    }
    private List<FlashcardCard> select(List<FlashcardCard> input, Map<Long, FlashcardCardProgress> states, String filter,
                                       SpeedRecallMode mode) {
        Map<String, FlashcardCard> unique = new LinkedHashMap<>();
        for (FlashcardCard c : input) {
            if (!mode.eligible(c)) continue;
            FlashcardCardProgress p = states.get(c.getId());
            if ("LEARNING".equals(filter) && p != null && "MASTERED".equals(p.getStatus())) continue;
            if ("STARRED".equals(filter) && (p == null || !Boolean.TRUE.equals(p.getIsStarred()))) continue;
            unique.putIfAbsent(mode.question().of(c) + "\u0000" + mode.comparable(mode.answer().of(c)), c);
        }
        return new ArrayList<>(unique.values());
    }
    private static SpeedRecallMode mode(String name) {
        try {
            return SpeedRecallMode.parse(name);
        } catch (IllegalArgumentException e) {
            throw bad("Chế độ luyện không hợp lệ");
        }
    }
    private void requireUser(Long userId) { if (userId == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Vui lòng đăng nhập"); }
    private void lockUser(Long userId) {
        if (em.find(User.class, userId, LockModeType.PESSIMISTIC_WRITE) == null)
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Tài khoản không tồn tại");
    }
    public static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static Instant instant(LocalDateTime date) { return date == null ? null : date.toInstant(ZoneOffset.UTC); }
    private static ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private static ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }
}
