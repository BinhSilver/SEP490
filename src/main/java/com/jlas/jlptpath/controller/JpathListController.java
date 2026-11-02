package com.jlas.jlptpath.controller;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.auth.security.UserPrincipal;
import com.jlas.jlptpath.dto.JpathGrammarFilter;
import com.jlas.jlptpath.dto.JpathGrammarLessonView;
import com.jlas.jlptpath.dto.JpathGrammarUnitView;
import com.jlas.jlptpath.dto.JpathKanaRowView;
import com.jlas.jlptpath.dto.JpathKanjiFilter;
import com.jlas.jlptpath.dto.JpathKanjiRowView;
import com.jlas.jlptpath.dto.JpathPage;
import com.jlas.jlptpath.dto.JpathTopicCardView;
import com.jlas.jlptpath.dto.JpathVocabFilter;
import com.jlas.jlptpath.dto.JpathVocabRowView;
import com.jlas.jlptpath.service.JpathListService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * [JLAS-W0108] JLPT path lists: {@code /lists/topics|vocabulary|kanji|grammar|kana}, the grammar lesson
 * {@code /grammar/{code}}, {@code /notebook} and {@code /known}. The learner is always the principal; pages are 0-based.
 */
@RestController
@RequestMapping("/api/learning/jlpt")
@RequiredArgsConstructor
public class JpathListController {

    private static final String OK = "OK";

    private final JpathListService lists;

    @GetMapping("/lists/topics")
    public ResponseEntity<ApiResponse<List<JpathTopicCardView>>> topics(@AuthenticationPrincipal UserPrincipal user,
            @RequestParam String level) {
        return ResponseEntity.ok(ApiResponse.ok(OK, lists.topics(user.userId(), level)));
    }

    @GetMapping("/lists/vocabulary")
    public ResponseEntity<ApiResponse<JpathPage<JpathVocabRowView>>> vocabulary(
            @AuthenticationPrincipal UserPrincipal user, @RequestParam String level,
            @RequestParam(required = false) String topic, @RequestParam(required = false) String state,
            @RequestParam(required = false) String q, @RequestParam(defaultValue = "0") int page) {
        JpathVocabFilter filter = new JpathVocabFilter(level, topic, state, q, page);
        return ResponseEntity.ok(ApiResponse.ok(OK, lists.vocabulary(user.userId(), filter)));
    }

    @GetMapping("/lists/kanji")
    public ResponseEntity<ApiResponse<JpathPage<JpathKanjiRowView>>> kanji(@AuthenticationPrincipal UserPrincipal user,
            @RequestParam String level, @RequestParam(required = false) String q,
            @RequestParam(required = false) Integer radical, @RequestParam(required = false) String sort,
            @RequestParam(required = false) String view, @RequestParam(defaultValue = "0") int page) {
        JpathKanjiFilter filter = new JpathKanjiFilter(level, q, radical, sort, view, page);
        return ResponseEntity.ok(ApiResponse.ok(OK, lists.kanji(user.userId(), filter)));
    }

    @GetMapping("/lists/grammar")
    public ResponseEntity<ApiResponse<List<JpathGrammarUnitView>>> grammar(@AuthenticationPrincipal UserPrincipal user,
            @RequestParam String level, @RequestParam(required = false) Integer unit,
            @RequestParam(required = false) String q) {
        return ResponseEntity.ok(ApiResponse.ok(OK,
                lists.grammar(user.userId(), new JpathGrammarFilter(level, unit, q))));
    }

    @GetMapping("/lists/kana")
    public ResponseEntity<ApiResponse<List<JpathKanaRowView>>> kana(@AuthenticationPrincipal UserPrincipal user,
            @RequestParam(required = false) String script) {
        return ResponseEntity.ok(ApiResponse.ok(OK, lists.kana(user.userId(), script)));
    }

    @GetMapping("/grammar/{code}")
    public ResponseEntity<ApiResponse<JpathGrammarLessonView>> grammarLesson(
            @AuthenticationPrincipal UserPrincipal user, @PathVariable String code) {
        return ResponseEntity.ok(ApiResponse.ok(OK, lists.grammarLesson(user.userId(), code)));
    }

    @GetMapping("/notebook")
    public ResponseEntity<ApiResponse<JpathPage<JpathVocabRowView>>> notebook(
            @AuthenticationPrincipal UserPrincipal user, @RequestParam(required = false) String tab,
            @RequestParam(defaultValue = "0") int page) {
        return ResponseEntity.ok(ApiResponse.ok(OK, lists.notebook(user.userId(), tab, page)));
    }

    @GetMapping("/known")
    public ResponseEntity<ApiResponse<JpathPage<JpathVocabRowView>>> known(@AuthenticationPrincipal UserPrincipal user,
            @RequestParam(required = false) String tab, @RequestParam(defaultValue = "0") int page) {
        return ResponseEntity.ok(ApiResponse.ok(OK, lists.known(user.userId(), tab, page)));
    }
}
