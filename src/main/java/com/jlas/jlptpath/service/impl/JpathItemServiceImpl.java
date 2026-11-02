package com.jlas.jlptpath.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.jlptpath.dto.JpathCompoundView;
import com.jlas.jlptpath.dto.JpathExampleView;
import com.jlas.jlptpath.dto.JpathGrammarFacts;
import com.jlas.jlptpath.dto.JpathItemDetailView;
import com.jlas.jlptpath.dto.JpathItemStateView;
import com.jlas.jlptpath.dto.JpathKanaFacts;
import com.jlas.jlptpath.dto.JpathKanjiFacts;
import com.jlas.jlptpath.dto.JpathKanjiInWordView;
import com.jlas.jlptpath.dto.JpathKnownResult;
import com.jlas.jlptpath.entity.JpathGrammar;
import com.jlas.jlptpath.entity.JpathKana;
import com.jlas.jlptpath.entity.JpathKanji;
import com.jlas.jlptpath.entity.JpathKanjiCompound;
import com.jlas.jlptpath.entity.JpathUserItem;
import com.jlas.jlptpath.entity.JpathVocabTopic;
import com.jlas.jlptpath.entity.JpathVocabTopicId;
import com.jlas.jlptpath.entity.JpathVocabulary;
import com.jlas.jlptpath.enums.JpathItemState;
import com.jlas.jlptpath.enums.JpathItemType;
import com.jlas.jlptpath.enums.JpathNotebookSource;
import com.jlas.jlptpath.repository.JpathKanjiCompoundRepository;
import com.jlas.jlptpath.repository.JpathUserItemRepository;
import com.jlas.jlptpath.repository.JpathUserPathRepository;
import com.jlas.jlptpath.repository.JpathVocabTopicRepository;
import com.jlas.jlptpath.service.JpathItemService;
import com.jlas.jlptpath.util.JpathAudio;
import com.jlas.jlptpath.util.JpathStrokeSvg;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * [JLAS-W0108] Item detail and the list-drawer actions. Content goes through {@link JpathContentReader} (visibility
 * and "learnable"); state changes go through {@link JpathItemStateMachine} after the ACTIVE path lock.
 */
@Service
public class JpathItemServiceImpl implements JpathItemService {

    private static final Logger log = LoggerFactory.getLogger(JpathItemServiceImpl.class);
    private static final String NEW = "NEW";
    private static final String KNOWN_BY_USER = "USER";
    private static final Locale VI = Locale.forLanguageTag("vi");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final JpathContentReader content;
    private final JpathItemStateMachine states;
    private final JpathUserItemRepository items;
    private final JpathUserPathRepository paths;
    private final JpathKanjiCompoundRepository compounds;
    private final JpathVocabTopicRepository topics;

    public JpathItemServiceImpl(JpathContentReader content, JpathItemStateMachine states,
                                JpathUserItemRepository items, JpathUserPathRepository paths,
                                JpathKanjiCompoundRepository compounds, JpathVocabTopicRepository topics) {
        this.content = content;
        this.states = states;
        this.items = items;
        this.paths = paths;
        this.compounds = compounds;
        this.topics = topics;
    }

    // ---- detail --------------------------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public JpathItemDetailView detail(Long userId, JpathItemType type, String code) {
        Optional<JpathUserItem> row = row(userId, type, code);
        String state = row.map(i -> i.getState().name()).orElse(NEW);
        boolean hard = row.map(states::hard).orElse(false);
        return switch (type) {
            case VOCAB -> vocabDetail(userId, content.vocab(code).orElseThrow(JpathItemServiceImpl::notFound),
                    state, hard);
            case KANJI -> kanjiDetail(userId, content.kanji(code).orElseThrow(JpathItemServiceImpl::notFound),
                    state, hard);
            case GRAMMAR -> grammarDetail(content.grammar(code).orElseThrow(JpathItemServiceImpl::notFound),
                    state, hard);
            case KANA -> kanaDetail(content.kanaItem(code).orElseThrow(JpathItemServiceImpl::notFound), state, hard);
        };
    }

    private JpathItemDetailView vocabDetail(Long userId, JpathVocabulary v, String state, boolean hard) {
        String topic = topics.findById(new JpathVocabTopicId(v.getLevel(), v.getTopicKey()))
                .map(JpathVocabTopic::getTitleVi).orElse(null);
        return new JpathItemDetailView(JpathItemType.VOCAB, v.getCode(), v.getLevel(), v.getExpression(),
                v.getReading(), v.getRomaji(), v.getMeaningVi(), v.getWordTypeLabel(), topic,
                examples(JpathItemType.VOCAB, v.getCode()), v.getMnemonicVi(), List.of(),
                kanjiInWord(v.getExpression()), confusables(userId, JpathItemType.VOCAB, v.getCode()), null, null,
                null, state, hard, v.getDictionaryEntryId(), JpathAudio.url(v.getAudioPath(), null));
    }

    private JpathItemDetailView kanjiDetail(Long userId, JpathKanji k, String state, boolean hard) {
        String svg = JpathStrokeSvg.url(k.getCharacter().codePointAt(0));
        JpathKanjiFacts facts = new JpathKanjiFacts(k.getHanViet() == null ? null : k.getHanViet().toUpperCase(VI),
                strings(k.getCode(), "han_viet_alt_json", k.getHanVietAltJson()),
                strings(k.getCode(), "readings_on_json", k.getReadingsOnJson()),
                strings(k.getCode(), "readings_kun_json", k.getReadingsKunJson()),
                strings(k.getCode(), "level_readings_json", k.getLevelReadingsJson()), k.getStrokeCount(), k.getRadicalNumber(), k.getRadicalNameVi(),
                svg);
        return new JpathItemDetailView(JpathItemType.KANJI, k.getCode(), k.getLevel(), k.getCharacter(), null, null,
                k.getMeaningsVi(), null, null, examples(JpathItemType.KANJI, k.getCode()), k.getMnemonicVi(),
                compounds(userId, k.getCode()), List.of(), confusables(userId, JpathItemType.KANJI, k.getCode()),
                facts, null, null, state, hard, null, null);
    }

    private JpathItemDetailView grammarDetail(JpathGrammar g, String state, boolean hard) {
        JpathGrammarFacts facts = new JpathGrammarFacts(g.getPattern(), g.getFormation(), g.getUsageVi(),
                g.getNuanceVi());
        return new JpathItemDetailView(JpathItemType.GRAMMAR, g.getCode(), g.getLevel(), g.getPattern(), null, null,
                g.getMeaningVi(), null, null, examples(JpathItemType.GRAMMAR, g.getCode()), null, List.of(),
                List.of(), List.of(), null, facts, null, state, hard, null, null);
    }

    private JpathItemDetailView kanaDetail(JpathKana k, String state, boolean hard) {
        JpathKanaFacts facts = new JpathKanaFacts(k.getKana(), k.getRomaji(), k.getScript(), k.getRowCode(),
                JpathStrokeSvg.url(k.getKana().codePointAt(0)));
        return new JpathItemDetailView(JpathItemType.KANA, k.getCode(), null, k.getKana(), null, k.getRomaji(), null,
                null, null, List.of(), null, List.of(), List.of(), List.of(), null, null, facts, state, hard, null,
                JpathAudio.url(null, k.getKana()));
    }

    private List<JpathExampleView> examples(JpathItemType type, String code) {
        return content.examples(type.name(), code).stream()
                .map(e -> new JpathExampleView(e.getCode(), e.getRole(), e.getJa(), e.getJaFurigana(), e.getVi(),
                        JpathAudio.url(e.getAudioPath(), null)))
                .toList();
    }

    /** Every distinct CJK character of {@code expression}, with the visible path kanji when there is one. */
    private List<JpathKanjiInWordView> kanjiInWord(String expression) {
        Set<Integer> cps = new LinkedHashSet<>();
        expression.codePoints().filter(JpathItemServiceImpl::isCjk).forEach(cps::add);
        List<JpathKanjiInWordView> out = new ArrayList<>(cps.size());
        for (int cp : cps) {
            String ch = new String(Character.toChars(cp));
            Optional<JpathKanji> k = content.kanjiByCharacter(ch);
            out.add(new JpathKanjiInWordView(ch, k.map(JpathKanji::getCode).orElse(null),
                    k.map(JpathKanji::getHanViet).orElse(null), k.map(JpathKanji::getMeaningsVi).orElse(null),
                    JpathStrokeSvg.url(cp), k.map(JpathKanji::getStrokeCount).orElse(null)));
        }
        return out;
    }

    /** Visible compound words of a kanji by sort order. */
    private List<JpathCompoundView> compounds(Long userId, String kanjiCode) {
        List<String> codes = compounds.findByKanjiCodeOrderBySortOrder(kanjiCode).stream()
                .map(JpathKanjiCompound::getVocabCode)
                .toList();
        return wordViews(userId, content.vocabs(codes));
    }

    private List<JpathCompoundView> confusables(Long userId, JpathItemType type, String code) {
        Optional<String> partner = content.confusablePartner(type, code);
        if (partner.isEmpty()) {
            return List.of();
        }
        if (type == JpathItemType.VOCAB) {
            return wordViews(userId, content.vocab(partner.get()).stream().toList());
        }
        Set<String> learned = learnedCodes(userId, JpathItemType.KANJI, List.of(partner.get()));
        return content.kanji(partner.get()).stream()
                .map(k -> new JpathCompoundView(k.getCode(), k.getCharacter(), null, k.getMeaningsVi(),
                        learned.contains(k.getCode())))
                .toList();
    }

    private List<JpathCompoundView> wordViews(Long userId, List<JpathVocabulary> words) {
        if (words.isEmpty()) {
            return List.of();
        }
        Set<String> learned = learnedCodes(userId, JpathItemType.VOCAB,
                words.stream().map(JpathVocabulary::getCode).toList());
        return words.stream()
                .map(w -> new JpathCompoundView(w.getCode(), w.getExpression(), w.getReading(), w.getMeaningVi(),
                        learned.contains(w.getCode())))
                .toList();
    }

    /** Codes among {@code codes} the learner has counted or knows. */
    private Set<String> learnedCodes(Long userId, JpathItemType type, List<String> codes) {
        Map<String, JpathUserItem> rows = items.findByUserIdAndItemTypeAndItemCodeIn(userId, type, codes).stream()
                .collect(Collectors.toMap(JpathUserItem::getItemCode, Function.identity()));
        return rows.values().stream()
                .filter(i -> i.getCountedAt() != null || i.getState() == JpathItemState.KNOWN)
                .map(JpathUserItem::getItemCode)
                .collect(Collectors.toSet());
    }

    // ---- known ---------------------------------------------------------------------------------------------

    @Override
    @Transactional
    public JpathKnownResult markKnown(Long userId, JpathItemType type, String code) {
        lockActivePath(userId);
        JpathUserItem i = row(userId, type, code).orElseThrow(JpathItemServiceImpl::notFound);
        if (!visible(type, code)) {
            throw notFound();
        }
        if (i.getState() != JpathItemState.KNOWN) {
            states.markKnown(i, KNOWN_BY_USER);
        }
        return new JpathKnownResult(JpathItemState.KNOWN.name(), null);
    }

    // ---- notebook ------------------------------------------------------------------------------------------

    @Override
    @Transactional
    public JpathItemStateView addToNotebook(Long userId, JpathItemType type, String code,
                                            JpathNotebookSource source) {
        lockActivePath(userId);
        if (type != JpathItemType.VOCAB && type != JpathItemType.KANJI) {
            throw new ApiException(ErrorCode.JPATH_GRAMMAR_NO_NOTEBOOK);
        }
        String level = learnableLevel(type, code);
        JpathUserItem i = states.ensureRow(userId, type, code, level);
        if (i.getState() == JpathItemState.NOTEBOOK) {
            return stateView(type, code, Optional.of(i)); // already there: keep source, since and hits
        }
        // KNOWN → NOTEBOOK by hand is intended: toNotebook clears known_at / known_source.
        states.toNotebook(i, source);
        return stateView(type, code, Optional.of(i));
    }

    @Override
    @Transactional
    public JpathItemStateView removeFromNotebook(Long userId, JpathItemType type, String code) {
        lockActivePath(userId);
        Optional<JpathUserItem> row = row(userId, type, code);
        if (row.isEmpty() && !visible(type, code)) {
            throw notFound();
        }
        row.filter(i -> i.getState() == JpathItemState.NOTEBOOK).ifPresent(states::leaveNotebook);
        return stateView(type, code, row);
    }

    // ---- helpers -------------------------------------------------------------------------------------------

    private void lockActivePath(Long userId) {
        paths.findActiveForUpdate(userId).orElseThrow(() -> new ApiException(ErrorCode.JPATH_NO_ACTIVE_PATH));
    }

    /** Level of a visible word or kanji that can be taught; hidden → ITEM_NOT_FOUND, incomplete → NOT_LEARNABLE. */
    private String learnableLevel(JpathItemType type, String code) {
        if (type == JpathItemType.VOCAB) {
            JpathVocabulary v = content.vocab(code).orElseThrow(JpathItemServiceImpl::notFound);
            if (!content.learnable(v)) {
                throw new ApiException(ErrorCode.JPATH_CONTENT_NOT_LEARNABLE);
            }
            return v.getLevel();
        }
        JpathKanji k = content.kanji(code).orElseThrow(JpathItemServiceImpl::notFound);
        if (!content.learnable(k)) {
            throw new ApiException(ErrorCode.JPATH_CONTENT_NOT_LEARNABLE);
        }
        return k.getLevel();
    }

    /** Same visibility rule as {@link #detail}: the item reads through {@link JpathContentReader}. */
    private boolean visible(JpathItemType type, String code) {
        return switch (type) {
            case VOCAB -> content.vocab(code).isPresent();
            case KANJI -> content.kanji(code).isPresent();
            case GRAMMAR -> content.grammar(code).isPresent();
            case KANA -> content.kanaItem(code).isPresent();
        };
    }

    private Optional<JpathUserItem> row(Long userId, JpathItemType type, String code) {
        return code == null ? Optional.empty() : items.findByUserIdAndItemTypeAndItemCode(userId, type, code);
    }

    private static JpathItemStateView stateView(JpathItemType type, String code, Optional<JpathUserItem> row) {
        JpathItemState s = row.map(JpathUserItem::getState).orElse(null);
        return new JpathItemStateView(type, code, s == null ? NEW : s.name(), s == JpathItemState.NOTEBOOK);
    }

    private static ApiException notFound() {
        return new ApiException(ErrorCode.JPATH_ITEM_NOT_FOUND);
    }

    /** Han ideographs; the repeat mark 々 is skipped (it repeats the kanji before it). */
    private static boolean isCjk(int cp) {
        return cp != 0x3005 && Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN;
    }

    /** A JSON string array as a list; malformed JSON is logged (item code + field) and reads as empty. */
    private static List<String> strings(String code, String field, String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonNode node = JSON.readTree(json);
            if (node == null || !node.isArray()) {
                return List.of();
            }
            List<String> out = new ArrayList<>(node.size());
            node.forEach(n -> out.add(n.asText()));
            return out;
        } catch (Exception e) {
            log.warn("Malformed JSON in {} of item {}: {}", field, code, e.getMessage());
            return List.of();
        }
    }
}
