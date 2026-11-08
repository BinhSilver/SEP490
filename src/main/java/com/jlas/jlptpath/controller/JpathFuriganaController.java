package com.jlas.jlptpath.controller;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.jlptpath.dto.JpathFuriganaRequest;
import com.jlas.learning.service.impl.text.GeneratedFurigana;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * [JLAS-W0108] Generated furigana for the Đọc · Nghe pages ({@code POST /api/learning/jlpt/furigana}). Skill passages,
 * questions and scripts carry no reviewed furigana, so the toggle on those pages asks for a reading aid derived from the
 * dictionary readings ({@link GeneratedFurigana}). The answer, in the path's {@code 漢字(かな)} form, is one entry per
 * text in order; null when a text has no Kanji or no reading could be derived. Nothing is stored.
 */
@RestController
@RequestMapping("/api/learning/jlpt/furigana")
public class JpathFuriganaController {

    private static final Pattern MARKUP = Pattern.compile("\\{([^{}|]+)\\|([^{}|]+)}");

    @PostMapping
    public ResponseEntity<ApiResponse<List<String>>> furigana(@Valid @RequestBody JpathFuriganaRequest request) {
        List<String> out = new ArrayList<>(request.texts().size());
        for (String text : request.texts()) out.add(toPathForm(GeneratedFurigana.markup(text)));
        return ResponseEntity.ok(ApiResponse.ok("OK", out));
    }

    /** {@code {漢字|かんじ}} → {@code 漢字(かんじ)}, the form JpathFurigana.toRuby reads. */
    static String toPathForm(String markup) {
        return markup == null ? null : MARKUP.matcher(markup).replaceAll("$1($2)");
    }
}
