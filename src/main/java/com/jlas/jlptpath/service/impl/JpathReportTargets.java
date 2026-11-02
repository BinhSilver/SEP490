package com.jlas.jlptpath.service.impl;

import com.jlas.jlptpath.dto.JpathReportTargetRef;
import com.jlas.jlptpath.entity.JpathExample;
import com.jlas.jlptpath.entity.JpathGrammarQuestion;
import com.jlas.jlptpath.entity.JpathKana;
import com.jlas.jlptpath.entity.JpathKanji;
import com.jlas.jlptpath.entity.JpathVocabulary;
import com.jlas.jlptpath.enums.JpathItemType;
import com.jlas.jlptpath.enums.JpathReportTarget;
import com.jlas.jlptpath.repository.JpathExampleRepository;
import com.jlas.jlptpath.repository.JpathGrammarQuestionRepository;
import com.jlas.jlptpath.repository.JpathGrammarRepository;
import com.jlas.jlptpath.repository.JpathKanaRepository;
import com.jlas.jlptpath.repository.JpathKanjiRepository;
import com.jlas.jlptpath.repository.JpathVocabularyRepository;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * [JLAS-W0108] Finds the row behind a report target, whatever its status (the editor queue also shows HOLD rows), and
 * derives the owning item on the server: EXAMPLE → its owner, QUESTION → GRAMMAR / grammar_code, KANA → KANA with no
 * level, never authored. Shared by the learner report API and the editor queue.
 */
@Component
public class JpathReportTargets {

    private static final int PREVIEW_MAX = 120;

    private final JpathVocabularyRepository vocabularies;
    private final JpathKanjiRepository kanjis;
    private final JpathGrammarRepository grammars;
    private final JpathKanaRepository kanas;
    private final JpathExampleRepository examples;
    private final JpathGrammarQuestionRepository questions;

    public JpathReportTargets(JpathVocabularyRepository vocabularies, JpathKanjiRepository kanjis,
                              JpathGrammarRepository grammars, JpathKanaRepository kanas,
                              JpathExampleRepository examples, JpathGrammarQuestionRepository questions) {
        this.vocabularies = vocabularies;
        this.kanjis = kanjis;
        this.grammars = grammars;
        this.kanas = kanas;
        this.examples = examples;
        this.questions = questions;
    }

    /** The target {@code (type, code)}; empty when either is missing or no such row exists. */
    public Optional<JpathReportTargetRef> resolve(JpathReportTarget type, String code) {
        if (type == null || code == null || code.isBlank()) {
            return Optional.empty();
        }
        return switch (type) {
            case VOCAB -> vocabularies.findById(code).map(v -> new JpathReportTargetRef(type, code,
                    JpathItemType.VOCAB, code, v.getLevel(), v.isAuthored(), v.isReportHidden()));
            case KANJI -> kanjis.findById(code).map(k -> new JpathReportTargetRef(type, code,
                    JpathItemType.KANJI, code, k.getLevel(), k.isAuthored(), k.isReportHidden()));
            case GRAMMAR -> grammars.findById(code).map(g -> new JpathReportTargetRef(type, code,
                    JpathItemType.GRAMMAR, code, g.getLevel(), g.isAuthored(), g.isReportHidden()));
            case KANA -> kanas.findById(code).map(k -> new JpathReportTargetRef(type, code,
                    JpathItemType.KANA, code, null, false, false));
            case EXAMPLE -> examples.findById(code).map(e -> new JpathReportTargetRef(type, code,
                    e.getOwnerType(), e.getOwnerCode(), e.getLevel(), e.isAuthored(), e.isReportHidden()));
            case QUESTION -> questions.findById(code).map(q -> new JpathReportTargetRef(type, code,
                    JpathItemType.GRAMMAR, q.getGrammarCode(), q.getLevel(), q.isAuthored(), q.isReportHidden()));
            // Plan 3: skill sets are reportable once the report service wires them; until then not found.
            case SKILL_SET, SKILL_QUESTION -> Optional.empty();
        };
    }

    /** Sets report_hidden on the target row itself (an example, not its owner). Kana have no such flag: no-op. */
    public void setHidden(JpathReportTargetRef ref, boolean hidden) {
        switch (ref.type()) {
            case VOCAB -> vocabularies.findById(ref.code()).ifPresent(v -> {
                v.setReportHidden(hidden);
                vocabularies.save(v);
            });
            case KANJI -> kanjis.findById(ref.code()).ifPresent(k -> {
                k.setReportHidden(hidden);
                kanjis.save(k);
            });
            case GRAMMAR -> grammars.findById(ref.code()).ifPresent(g -> {
                g.setReportHidden(hidden);
                grammars.save(g);
            });
            case EXAMPLE -> examples.findById(ref.code()).ifPresent(e -> {
                e.setReportHidden(hidden);
                examples.save(e);
            });
            case QUESTION -> questions.findById(ref.code()).ifPresent(q -> {
                q.setReportHidden(hidden);
                questions.save(q);
            });
            case KANA, SKILL_SET, SKILL_QUESTION -> {
                // kana are never hidden by reports; skill targets are not resolvable yet (see findRef)
            }
        }
    }

    /** One-line text of the target for the editor queue (at most 120 characters); empty when the row is gone. */
    public String preview(JpathReportTargetRef ref) {
        String text = switch (ref.type()) {
            case VOCAB -> vocabularies.findById(ref.code()).map(JpathReportTargets::vocabText).orElse("");
            case KANJI -> kanjis.findById(ref.code()).map(JpathReportTargets::kanjiText).orElse("");
            case GRAMMAR -> grammars.findById(ref.code()).map(g -> join(g.getPattern(), g.getMeaningVi())).orElse("");
            case KANA -> kanas.findById(ref.code()).map(JpathReportTargets::kanaText).orElse("");
            case EXAMPLE -> examples.findById(ref.code()).map(JpathExample::getJa).orElse("");
            case QUESTION -> questions.findById(ref.code()).map(JpathGrammarQuestion::getPromptJa).orElse("");
            case SKILL_SET, SKILL_QUESTION -> "";
        };
        return shorten(text == null ? "" : text);
    }

    private static String vocabText(JpathVocabulary v) {
        String word = v.getReading() == null || v.getReading().equals(v.getExpression())
                ? v.getExpression() : v.getExpression() + " (" + v.getReading() + ")";
        return join(word, v.getMeaningVi());
    }

    private static String kanjiText(JpathKanji k) {
        return join(k.getCharacter(), k.getMeaningsVi());
    }

    private static String kanaText(JpathKana k) {
        return k.getKana() + " (" + k.getRomaji() + ")";
    }

    private static String join(String head, String meaning) {
        return meaning == null || meaning.isBlank() ? head : head + " — " + meaning;
    }

    private static String shorten(String text) {
        if (text.codePointCount(0, text.length()) <= PREVIEW_MAX) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, PREVIEW_MAX - 1)) + "…";
    }
}
