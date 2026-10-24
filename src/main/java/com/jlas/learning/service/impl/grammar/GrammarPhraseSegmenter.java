package com.jlas.learning.service.impl.grammar;

import com.atilika.kuromoji.ipadic.Token;
import com.atilika.kuromoji.ipadic.Tokenizer;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Ordering and fill-blank material for one grammar point's example sentence.
 *
 * <p>JLPT seed examples are already whitespace-segmented, and those units are kept as they are. Communication examples
 * (owner 2026-10-21) are plain Japanese, so they are cut into phrase chunks (文節-like: a content word with the particles
 * and auxiliaries after it, compound nouns kept whole) by the bundled Kuromoji dictionary. The grammar pattern's literal
 * text becomes one chunk of its own and is the fill-blank answer. Deterministic, no AI. */
public final class GrammarPhraseSegmenter {
    public static final int MAX_PARTS = 8;
    /** Owner 2026-10-23: a tile this long reads as several words stuck together; it is cut again at word level. */
    public static final int LONG_TILE = 9;
    /** Longest text (letters, punctuation excluded) practised as one ordering exercise. */
    static final int MAX_WINDOW = 36;
    private static final String BLANK = "_____";
    private static final String PUNCTUATION_ONLY = "[、。！？「」…・\\s]+";
    private static final Set<String> CONTENT = Set.of("名詞", "動詞", "形容詞", "副詞", "連体詞", "接続詞", "感動詞", "接頭詞");
    private static final Tokenizer TOKENIZER = new Tokenizer();

    private GrammarPhraseSegmenter() { }

    /** Ordered parts of the practised sentence; {@code blankIndex} names the fill-blank part. For a plain sentence the
     * blank is the character range [{@code fillStart}, {@code fillEnd}) — the pattern as used there — which need not be a
     * whole tile ("お願いします" in 領収書を / お願い / します). */
    public record Practice(List<String> parts, int blankIndex, boolean spaced, int fillStart, int fillEnd) {
        public Practice(List<String> parts, int blankIndex, boolean spaced) { this(parts, blankIndex, spaced, -1, -1); }
        public String text() { return String.join(spaced ? " " : "", parts); }
        /** The blank's text without the punctuation that rides on its tile; that punctuation stays visible in the prompt. */
        public String answer() {
            if (blankIndex < 0) return null;
            if (fillStart >= 0) return text().substring(fillStart, fillEnd);
            String part = parts.get(blankIndex);
            String bare = spaced ? part : part.replaceAll("[、。！？]+$", "");
            return bare.isEmpty() ? part : bare;
        }
        public String prompt() {
            if (blankIndex >= 0 && fillStart >= 0) return text().substring(0, fillStart) + BLANK + text().substring(fillEnd);
            List<String> shown = new ArrayList<>(parts);
            if (blankIndex >= 0) {
                String part = parts.get(blankIndex);
                shown.set(blankIndex, BLANK + part.substring(answer().length()));
            }
            return String.join(spaced ? " " : "", shown);
        }
    }

    public static Practice practice(String example, String pattern) {
        if (example == null || example.isBlank()) return new Practice(List.of(), -1, true);
        String text = example.trim();
        if (text.matches(".*\\s.*")) {
            List<String> words = GrammarTextAnalyzer.splitWords(text);
            return new Practice(words, GrammarTextAnalyzer.chooseBlankIndex(words), true);
        }
        String core = patternCore(pattern);
        Match match = window(locate(text, core));
        String sentence = match.sentence();
        List<int[]> spans = chunkSpans(sentence, false);
        if (spans.size() < 3) spans = chunkSpans(sentence, true);
        int blank;
        // A pattern that is most of the sentence would leave two tiles; keep the phrase chunks and blank its ending instead.
        boolean isolatePattern = match.exact() && match.length() * 10 < letters(sentence) * 6;
        if (isolatePattern) {
            spans = isolate(spans, match.start(), match.start() + match.length());
            spans = refineLong(spans, sentence, match.start(), match.start() + match.length());
            spans = capParts(spans, match.start());
            blank = indexOfStart(spans, match.start());
        } else {
            spans = refineLong(spans, sentence, match.start(), match.start() < 0 ? -1 : match.start() + match.length());
            spans = capParts(spans, -1);
            blank = match.start() < 0 ? -1 : indexContaining(spans, match.start() + match.length() - 1);
        }
        int blankStart = blank < 0 ? -1 : spans.get(blank)[0];
        spans = attachPunctuation(spans, sentence);
        if (blankStart >= 0) blank = indexContaining(spans, blankStart);
        List<String> parts = spans.stream().map(span -> sentence.substring(span[0], span[1])).toList();
        if (blank < 0) blank = GrammarTextAnalyzer.chooseBlankIndex(parts);
        if (blank < 0) return new Practice(parts, -1, false);
        // The fill blanks the pattern itself when it is a clear part of the sentence; otherwise the chosen tile.
        int fillStart, fillEnd;
        if (match.start() >= 0 && match.exact() && match.length() * 10 < letters(sentence) * 9) {
            fillStart = match.start();
            fillEnd = match.start() + match.length();
        } else if (match.start() >= 0 && !match.exact()) {
            // A conjugated use (止まら → 止まらなくて): blank it up to the end of its tile.
            fillStart = match.start();
            fillEnd = spans.get(indexContaining(spans, match.start() + match.length() - 1))[1];
        } else {
            fillStart = spans.get(blank)[0];
            fillEnd = spans.get(blank)[1];
        }
        while (fillEnd > fillStart + 1 && "、。！？".indexOf(sentence.charAt(fillEnd - 1)) >= 0) fillEnd--;
        return new Practice(parts, blank, false, fillStart, fillEnd);
    }

    /** Where the pattern sits: the exact literal if a sentence has it, else the longest leading piece of it (a conjugated
     * use such as 止まらなくて for 〜が止まらない), else nothing — the first sentence is then practised on its own. */
    private record Match(String sentence, int start, int length, boolean exact) { }

    private static Match locate(String text, String core) {
        List<String> sentences = sentences(text);
        if (core != null) {
            for (String sentence : sentences) if (sentence.contains(core)) return new Match(sentence, sentence.indexOf(core), core.length(), true);
            // "〜をお願いします" also covers "お水を一杯お願いします": the particle belongs to the slot, not the pattern.
            String bare = withoutLeadingParticle(core);
            if (!bare.equals(core) && bare.length() >= 2) {
                for (String sentence : sentences) if (sentence.contains(bare)) return new Match(sentence, sentence.indexOf(bare), bare.length(), true);
            }
            for (int length = core.length() - 1; length >= 2; length--) {
                String head = core.substring(0, length);
                for (String sentence : sentences) if (sentence.contains(head)) return new Match(sentence, sentence.indexOf(head), length, false);
            }
        }
        return new Match(sentences.get(0), -1, 0, false);
    }

    private static int letters(String sentence) { return sentence.replaceAll("[、。！？「」]", "").length(); }

    /** Owner 2026-10-23 ("dính liền"): a long business sentence cut into at most {@link #MAX_PARTS} tiles leaves whole
     * phrases glued together. Practise the clauses (split at 、) around the pattern instead, preferring earlier clauses
     * so the practised text keeps the sentence ending; the full example stays in the lesson above the exercise. */
    private static Match window(Match match) {
        String sentence = match.sentence();
        if (match.start() < 0 || letters(sentence) <= MAX_WINDOW) return match;
        List<int[]> clauses = new ArrayList<>();
        int from = 0;
        for (int i = 0; i < sentence.length(); i++) {
            if (sentence.charAt(i) == '、') { clauses.add(new int[]{from, i + 1}); from = i + 1; }
        }
        if (from < sentence.length()) clauses.add(new int[]{from, sentence.length()});
        int first = indexContaining(clauses, match.start());
        int last = indexContaining(clauses, Math.max(match.start(), match.start() + match.length() - 1));
        if (first < 0 || last < 0) return match;
        while (first > 0 && letters(sentence.substring(clauses.get(first - 1)[0], clauses.get(last)[1])) <= MAX_WINDOW) first--;
        while (last + 1 < clauses.size() && letters(sentence.substring(clauses.get(first)[0], clauses.get(last + 1)[1])) <= MAX_WINDOW) last++;
        int start = clauses.get(first)[0], end = clauses.get(last)[1];
        if (start == 0 && end == sentence.length()) return match;
        return new Match(sentence.substring(start, end), match.start() - start, match.length(), match.exact());
    }

    /** The longest literal piece of a pattern such as "〜を担当いたします" or "私、〇〇が承りました". */
    /** "をお願いします" → "お願いします"; a pattern that starts with a content word is returned unchanged. */
    public static String withoutLeadingParticle(String core) {
        return core == null ? null : core.replaceFirst("^[をにがはでとものへや]", "");
    }

    public static String patternCore(String pattern) {
        if (pattern == null) return null;
        String best = null;
        for (String piece : pattern.split("[〜～~〇○◯…]+|\\s+|\\b[A-Z]\\d*\\b")) {
            String literal = piece.replaceAll("[。、！？]+$", "").trim();
            if (!literal.isEmpty() && (best == null || literal.length() > best.length())) best = literal;
        }
        return best;
    }

    private static List<String> sentences(String text) {
        List<String> sentences = new ArrayList<>();
        int from = 0;
        for (int i = 0; i < text.length(); i++) {
            if ("。！？".indexOf(text.charAt(i)) >= 0) {
                if (!text.substring(from, i + 1).isBlank()) sentences.add(text.substring(from, i + 1).trim());
                from = i + 1;
            }
        }
        if (!text.substring(from).isBlank()) sentences.add(text.substring(from).trim());
        return sentences;
    }

    /** Character spans of phrase chunks: a new chunk starts at a content word unless it continues a compound noun, follows a
     * prefix, or is a dependent/suffix form; particles, auxiliaries and punctuation stay with the chunk before them. */
    private static List<int[]> chunkSpans(String sentence, boolean fine) {
        List<int[]> spans = new ArrayList<>();
        Token previous = null;
        for (Token token : TOKENIZER.tokenize(sentence)) {
            int start = token.getPosition(), end = start + token.getSurface().length();
            boolean startsChunk = spans.isEmpty() || startsChunk(token, previous, fine);
            if (startsChunk) spans.add(new int[]{start, end});
            else spans.get(spans.size() - 1)[1] = end;
            previous = token;
        }
        return spans;
    }

    /** {@code fine} cuts short one-phrase sentences further: compound nouns and する-verbs split into their words. */
    private static boolean startsChunk(Token token, Token previous, boolean fine) {
        String pos = token.getPartOfSpeechLevel1(), sub = token.getPartOfSpeechLevel2();
        // Fine cuts split a する-verb from its noun (お願い / します) but keep compound nouns (領収書) whole.
        if (fine) return !Set.of("助詞", "助動詞", "記号").contains(pos) && (previous == null
                || !"接頭詞".equals(previous.getPartOfSpeechLevel1())
                && !("名詞".equals(pos) && "名詞".equals(previous.getPartOfSpeechLevel1())));
        if (!CONTENT.contains(pos) || "非自立".equals(sub) || "接尾".equals(sub)) return false;
        if (previous == null) return true;
        String prevPos = previous.getPartOfSpeechLevel1();
        if ("接頭詞".equals(prevPos)) return false;
        if ("名詞".equals(pos) && "名詞".equals(prevPos)) return false;
        // A verb/adjective right after its own stem noun (e.g. 実施-中, 完了-し) belongs to that word.
        return !("動詞".equals(pos) && "名詞".equals(prevPos) && "サ変接続".equals(previous.getPartOfSpeechLevel2()));
    }

    /** Re-cuts the spans so that [start, end) is exactly one chunk. */
    private static List<int[]> isolate(List<int[]> spans, int start, int end) {
        List<int[]> cuts = new ArrayList<>();
        for (int[] span : spans) {
            int a = span[0], b = span[1];
            if (b <= start || a >= end) { cuts.add(new int[]{a, b}); continue; }
            if (a < start) cuts.add(new int[]{a, start});
            if (b > end) cuts.add(new int[]{end, b});
        }
        cuts.add(new int[]{start, end});
        cuts.sort((x, y) -> Integer.compare(x[0], y[0]));
        return cuts;
    }

    /** Merges the shortest neighbouring pair (never the pattern chunk) until at most {@link #MAX_PARTS} remain; a merge that
     * stays under {@link #LONG_TILE} is preferred over one that would glue words back into a long tile. */
    private static List<int[]> capParts(List<int[]> spans, int coreStart) {
        List<int[]> parts = new ArrayList<>(spans);
        while (parts.size() > MAX_PARTS) {
            int best = -1, bestLength = Integer.MAX_VALUE;
            for (int i = 0; i + 1 < parts.size(); i++) {
                if (coreStart >= 0 && (parts.get(i)[0] == coreStart || parts.get(i + 1)[0] == coreStart)) continue;
                int length = parts.get(i + 1)[1] - parts.get(i)[0];
                int rank = length < LONG_TILE ? length : length + 1000;
                if (rank < bestLength) { best = i; bestLength = rank; }
            }
            if (best < 0) break;
            parts.set(best, new int[]{parts.get(best)[0], parts.get(best + 1)[1]});
            parts.remove(best + 1);
        }
        return parts;
    }

    /** Long tiles outside the pattern [patternStart, patternEnd) are re-cut with the fine rules; a tile that does not
     * split into at least two pieces stays as it was. */
    private static List<int[]> refineLong(List<int[]> spans, String sentence, int patternStart, int patternEnd) {
        List<int[]> out = new ArrayList<>();
        for (int[] span : spans) {
            boolean holdsPattern = patternStart >= 0 && span[0] < patternEnd && patternStart < span[1];
            String piece = sentence.substring(span[0], span[1]);
            List<int[]> fine = holdsPattern || letters(piece) < LONG_TILE ? List.of() : chunkSpans(piece, true);
            if (fine.size() < 2) { out.add(span); continue; }
            for (int[] part : fine) out.add(new int[]{span[0] + part[0], span[0] + part[1]});
        }
        return out;
    }

    /** A chunk of punctuation only (left over after isolating the pattern) joins the chunk before it, and a chunk that
     * starts with 、/。 (a merge across the pattern's edge, "、工数と") hands that punctuation back to the chunk before. */
    private static List<int[]> attachPunctuation(List<int[]> spans, String sentence) {
        List<int[]> out = new ArrayList<>();
        for (int[] span : spans) {
            boolean punctuation = sentence.substring(span[0], span[1]).matches(PUNCTUATION_ONLY);
            if (punctuation && !out.isEmpty()) { out.get(out.size() - 1)[1] = span[1]; continue; }
            int start = span[0];
            while (!out.isEmpty() && start < span[1] - 1 && "、。！？".indexOf(sentence.charAt(start)) >= 0) start++;
            if (start > span[0]) out.get(out.size() - 1)[1] = start;
            out.add(new int[]{start, span[1]});
        }
        if (out.size() > 1 && sentence.substring(out.get(0)[0], out.get(0)[1]).matches(PUNCTUATION_ONLY)) {
            out.get(1)[0] = out.get(0)[0];
            out.remove(0);
        }
        return out;
    }

    private static int indexContaining(List<int[]> spans, int offset) {
        for (int i = 0; i < spans.size(); i++) if (spans.get(i)[0] <= offset && offset < spans.get(i)[1]) return i;
        return -1;
    }

    private static int indexOfStart(List<int[]> spans, int start) {
        for (int i = 0; i < spans.size(); i++) if (spans.get(i)[0] == start) return i;
        return -1;
    }
}
