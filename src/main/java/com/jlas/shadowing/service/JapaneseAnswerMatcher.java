package com.jlas.shadowing.service;

import com.atilika.kuromoji.ipadic.Tokenizer;
import com.jlas.common.util.RomajiKana;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Matches Japanese answers written in kanji, kana or romaji against approved transcript readings.
 * Only readings explicitly attached to the expected kanji are accepted, so an unrelated
 * homophonous kanji is not treated as correct.
 */
final class JapaneseAnswerMatcher {
    private static final Pattern FURIGANA_ANNOTATION = Pattern.compile(
            "([\\p{IsHan}\\u3005\\u3006\\u30F5\\u30F6]+)[\\uFF08(]([^\\uFF09)]+)[\\uFF09)]");
    /**
     * Word-level furigana as stored by the Shadowing pipeline: "近く(ちかく) に 住ん(すん) で", one reading for a
     * whole space-separated word including its okurigana. The spaces are separators, not part of the text.
     */
    private static final Pattern WORD_ANNOTATION = Pattern.compile(
            "(?<!\\S)([^\\s\\uFF08\\uFF09()]*[\\p{IsHan}\\u3005\\u3006\\u30F5\\u30F6][^\\s\\uFF08\\uFF09()]*)"
                    + "[\\uFF08(]([^\\uFF09)]+)[\\uFF09)]");
    private static final int MAX_MIXED_KANJI = 6;
    private static final Pattern ROMAJI_INPUT = Pattern.compile("[A-Za-zāīūēōĀĪŪĒŌ]");
    private static final Map<String, String> PARTICLE_READINGS = Map.of("は", "わ", "へ", "え", "を", "お");
    private static final List<String> VOWEL_ROWS = List.of("aあかさたなはまやらわがざだばぱぁゃ",
            "iいきしちにひみりぎじぢびぴぃ", "uうくすつぬふむゆるぐずづぶぷゔぅゅ",
            "eえけせてねへめれげぜでべぺぇ", "oおこそとのほもよろをごぞどぼぽぉょ");

    private JapaneseAnswerMatcher() {
    }

    static MatchResult matchSentence(String expected, String furigana, String actual) {
        List<Segment> segments = sentenceSegments(expected, furigana);
        return match(expected, actual, segments);
    }

    static MatchResult matchCloze(String sentence, String furigana, String expected,
                                   String expectedReading, int occurrence, String actual) {
        List<Segment> segments = clozeSegments(sentence, furigana, expected, occurrence);
        if (segments.isEmpty()) {
            segments = singleSegment(expected, expectedReading);
        } else if (isUsableReading(expectedReading)) {
            String normalizedActual = normalizeKana(actual);
            if (normalizeKana(expectedReading).equals(normalizedActual)
                    || matchesRomaji(singleSegment(expected, expectedReading), actual)) {
                return result(expected, actual, MatchKind.EQUIVALENT, segments);
            }
        }
        return match(expected, actual, segments);
    }

    /** Kana reading of a blank, or null when a kanji in it has no approved reading (only kanji would be accepted). */
    static String clozeReading(String sentence, String furigana, String expected, int occurrence) {
        return reading(clozeSegments(sentence, furigana, expected, occurrence), expected);
    }

    /** Kana reading of a whole dictated sentence, or null when a kanji in it has no approved reading. */
    static String sentenceReading(String expected, String furigana) {
        return reading(sentenceSegments(expected, furigana), expected);
    }

    private static String reading(List<Segment> segments, String expected) {
        if (segments.isEmpty()) segments = singleSegment(expected, "");
        StringBuilder kana = new StringBuilder();
        for (Segment segment : segments) {
            String reading = segment.readings().stream().filter(JapaneseAnswerMatcher::isUsableReading)
                    .findFirst().orElse(null);
            if (reading != null) kana.append(normalizeKana(reading));
            else if (segment.surface().codePoints().anyMatch(JapaneseAnswerMatcher::isKanji)) return null;
            else kana.append(normalizeKana(segment.surface()));
        }
        return kana.toString();
    }

    static String normalize(String value) {
        if (value == null) return "";
        return Normalizer.normalize(value, Normalizer.Form.NFKC).codePoints()
                .filter(cp -> !isIgnored(cp))
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString();
    }

    private static MatchResult match(String expected, String actual, List<Segment> segments) {
        String normalizedExpected = normalize(expected);
        String normalizedActual = normalize(actual);
        if (normalizedExpected.equals(normalizedActual)) {
            return result(expected, actual, MatchKind.EXACT_SURFACE, segments);
        }
        if (matchesSegments(segments, normalizeKana(actual))) {
            return result(expected, actual, MatchKind.EQUIVALENT, segments);
        }
        if (matchesRomaji(segments, actual)) {
            return result(expected, actual, MatchKind.EQUIVALENT, segments);
        }
        return result(expected, actual, MatchKind.NONE, segments);
    }

    private static MatchResult result(String expected, String actual, MatchKind kind, List<Segment> segments) {
        boolean romaji = isRomaji(actual);
        List<Segment> comparedSegments = romaji ? romajiSegments(segments) : segments;
        String comparedActual = romaji ? romajiKana(actual) : normalizeKana(actual);
        int score = kind == MatchKind.NONE
                ? similarityScore(comparedSegments, comparedActual)
                : 100;
        return new MatchResult(kind, normalize(expected), normalize(actual), score,
                kind == MatchKind.NONE ? partialFeedback(comparedSegments, comparedActual, romaji) : List.of());
    }

    /**
     * Align against the answer's surface positions, rather than the submitted spelling's positions.
     * Reading alternatives span their entire approved kanji group: a fragment of a reading must not
     * reveal an unrelated kanji. Extras never move the output or expose the concealed answer.
     */
    private static List<FeedbackPart> partialFeedback(List<Segment> segments, String actual, boolean romaji) {
        List<Integer> surface = new ArrayList<>();
        List<List<FeedbackOption>> options = new ArrayList<>();
        for (Segment segment : segments) {
            int[] characters = normalize(segment.surface()).codePoints().toArray();
            int start = surface.size();
            for (int character : characters) {
                surface.add(character);
                List<FeedbackOption> alternatives = new ArrayList<>();
                alternatives.add(new FeedbackOption(1, new int[]{katakanaToHiragana(character)}, false));
                options.add(alternatives);
            }
            if (characters.length == 0) continue;
            for (String reading : segment.readings()) {
                int[] value = normalizeKana(reading).codePoints().toArray();
                if (value.length > 0) options.get(start).add(new FeedbackOption(characters.length, value, romaji));
            }
            // Keep long-vowel alternatives local to a literal span, just as full-answer matching does.
            if (romaji && segment.readings().isEmpty()) {
                for (int index = 0; index + 1 < characters.length; index++) {
                    int[] pair = {katakanaToHiragana(characters[index]), katakanaToHiragana(characters[index + 1])};
                    if (romajiKey(new String(pair, 0, pair.length)).endsWith("ー")) {
                        options.get(start + index).add(new FeedbackOption(2, pair, true));
                    }
                }
            }
        }

        int[] submitted = actual.codePoints().toArray();
        int[][] matched = new int[surface.size() + 1][submitted.length + 1];
        for (int position = surface.size() - 1; position >= 0; position--) {
            for (int input = submitted.length - 1; input >= 0; input--) {
                int best = Math.max(matched[position + 1][input], matched[position][input + 1]);
                for (FeedbackOption option : options.get(position)) {
                    if (option.matches(submitted, input)) {
                        best = Math.max(best, option.span() + matched[position + option.span()][input + option.value().length]);
                    }
                }
                matched[position][input] = best;
            }
        }

        boolean[] visible = new boolean[surface.size()];
        int position = 0;
        int input = 0;
        while (position < surface.size() && input < submitted.length) {
            FeedbackOption selected = null;
            for (FeedbackOption option : options.get(position)) {
                if (option.matches(submitted, input) && matched[position][input]
                        == option.span() + matched[position + option.span()][input + option.value().length]) {
                    selected = option;
                    break;
                }
            }
            if (selected != null) {
                java.util.Arrays.fill(visible, position, position + selected.span(), true);
                position += selected.span();
                input += selected.value().length;
            } else if (matched[position][input + 1] == matched[position][input]) {
                input++;
            } else {
                position++;
            }
        }

        List<FeedbackPart> parts = new ArrayList<>();
        for (int start = 0; start < surface.size();) {
            boolean show = visible[start];
            StringBuilder text = new StringBuilder();
            int end = start;
            while (end < surface.size() && visible[end] == show) {
                if (show) text.appendCodePoint(surface.get(end));
                else text.append("..");
                end++;
            }
            parts.add(new FeedbackPart(show, text.toString()));
            start = end;
        }
        return List.copyOf(parts);
    }

    private static boolean isRomaji(String value) {
        return value != null && ROMAJI_INPUT.matcher(Normalizer.normalize(value, Normalizer.Form.NFKC)).find();
    }

    private static boolean matchesRomaji(List<Segment> segments, String actual) {
        return isRomaji(actual) && matchesSegments(romajiSegments(segments), romajiKana(actual), true);
    }

    private static List<Segment> romajiSegments(List<Segment> segments) {
        String surface = segments.stream().map(Segment::surface).collect(java.util.stream.Collectors.joining());
        Map<Integer, String> particles = new HashMap<>();
        // Use tokenization only for particle pronunciation; kanji readings still come from the approved transcript.
        RomajiTokenizer.INSTANCE.tokenize(surface).forEach(token -> {
            String reading = PARTICLE_READINGS.get(token.getSurface());
            if (reading != null && "助詞".equals(token.getPartOfSpeechLevel1())) {
                particles.put(token.getPosition(), reading);
            }
        });
        List<Segment> result = new ArrayList<>();
        int position = 0;
        for (Segment segment : segments) {
            int start = 0;
            if (segment.readings().isEmpty()) {
                for (int index = 0; index < segment.surface().length(); index++) {
                    String reading = particles.get(position + index);
                    if (reading == null) continue;
                    if (index > start) result.add(new Segment(segment.surface().substring(start, index), List.of()));
                    result.add(new Segment(segment.surface().substring(index, index + 1), List.of(reading)));
                    start = index + 1;
                }
            }
            if (start < segment.surface().length()) {
                result.add(new Segment(segment.surface().substring(start), segment.readings()));
            }
            position += segment.surface().length();
        }
        return result;
    }

    private static String romajiKana(String value) {
        // Convert before removing spaces/apostrophes: shin'you and shinyou have different readings.
        String raw = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT).replace('’', '\'')
                .replace("ā", "a-").replace("ī", "i-").replace("ū", "u-")
                .replace("ē", "e-").replace("ō", "o-");
        return normalizeKana(RomajiKana.toHiragana(raw));
    }

    private static String romajiKey(String value) {
        String kana = romajiKana(value);
        StringBuilder key = new StringBuilder();
        char previousVowel = 0;
        for (int index = 0; index < kana.length(); index++) {
            char current = kana.charAt(index);
            char vowel = vowelOf(current);
            boolean pureVowel = "あいうえお".indexOf(current) >= 0;
            if (pureVowel && previousVowel != 0 && (previousVowel == vowel
                    || previousVowel == 'o' && vowel == 'u' || previousVowel == 'e' && vowel == 'i')) {
                key.append('ー');
                previousVowel = 0;
            } else {
                key.append(current);
                previousVowel = vowel;
            }
        }
        return key.toString();
    }

    private static char vowelOf(char kana) {
        for (String row : VOWEL_ROWS) if (row.indexOf(kana, 1) >= 0) return row.charAt(0);
        return 0;
    }

    private static final class RomajiTokenizer {
        private static final Tokenizer INSTANCE = new Tokenizer();
    }

    private static List<Segment> sentenceSegments(String expected, String furigana) {
        ParsedSentence parsed = parse(furigana, expected);
        if (parsed == null || !normalize(parsed.surface()).equals(normalize(expected))) {
            return singleSegment(expected, "");
        }
        return parsed.segments();
    }

    private static List<Segment> clozeSegments(String sentence, String furigana,
                                               String expected, int occurrence) {
        ParsedSentence parsed = parse(furigana, sentence);
        if (parsed == null) return List.of();
        // Positions are counted on the text without the word-separating spaces of the furigana.
        sentence = withoutSpaces(sentence);
        expected = withoutSpaces(expected);

        int start = nthIndexOf(sentence, expected, occurrence);
        if (start < 0) return List.of();
        int end = start + expected.length();
        List<Segment> selected = new ArrayList<>();
        int cursor = 0;
        for (Segment segment : parsed.segments()) {
            int segmentStart = cursor;
            int segmentEnd = cursor + segment.surface().length();
            cursor = segmentEnd;
            if (segmentEnd <= start || segmentStart >= end) continue;

            int localStart = Math.max(start, segmentStart) - segmentStart;
            int localEnd = Math.min(end, segmentEnd) - segmentStart;
            if (localStart == 0 && localEnd == segment.surface().length()) {
                selected.add(segment);
            } else {
                selected.add(new Segment(segment.surface().substring(localStart, localEnd), List.of()));
            }
        }
        String selectedSurface = selected.stream().map(Segment::surface)
                .collect(java.util.stream.Collectors.joining());
        return selectedSurface.equals(expected) ? selected : List.of();
    }

    /**
     * Word-level ("贈り物(おくりもの) を") or kanji-level ("近(ちか)く") furigana, whichever reproduces the text; else null.
     * Word-level is tried first: read kanji-level, "贈り物(おくりもの)" would wrongly give 物 the whole word's reading.
     * It is only used when every word's kana agrees with its reading, so kanji-level "お茶(ちゃ)" is not misread.
     */
    private static ParsedSentence parse(String annotated, String text) {
        ParsedSentence words = parse(annotated, WORD_ANNOTATION);
        if (words != null && withoutSpaces(words.surface()).equals(withoutSpaces(text))
                && words.segments().stream().allMatch(JapaneseAnswerMatcher::kanaAgreesWithReading)) return words;
        ParsedSentence kanji = parse(annotated, FURIGANA_ANNOTATION);
        return kanji != null && withoutSpaces(kanji.surface()).equals(withoutSpaces(text)) ? kanji : null;
    }

    /** 贈り物 / おくりもの: the word's own kana (り) must appear in its reading, around the kanji. */
    private static boolean kanaAgreesWithReading(Segment segment) {
        if (segment.readings().isEmpty()) return true;
        StringBuilder shape = new StringBuilder();
        boolean inKanji = false;
        for (int codePoint : segment.surface().codePoints().toArray()) {
            // Digits and Latin letters are read out in kana too (3名 = さんめい), so like kanji they match any reading.
            if (isKanji(codePoint) || Character.isLetterOrDigit(codePoint) && codePoint < 0x3000) {
                if (!inKanji) shape.append(".+");
                inKanji = true;
            } else {
                shape.append(Pattern.quote(new String(Character.toChars(katakanaToHiragana(codePoint)))));
                inKanji = false;
            }
        }
        String reading = segment.readings().get(0).codePoints().map(JapaneseAnswerMatcher::katakanaToHiragana)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
        return reading.matches(shape.toString());
    }

    private static ParsedSentence parse(String annotated, Pattern pattern) {
        if (annotated == null || annotated.isBlank()) return null;
        Matcher matcher = pattern.matcher(annotated);
        List<Segment> segments = new ArrayList<>();
        StringBuilder surface = new StringBuilder();
        int cursor = 0;
        boolean foundAnnotation = false;
        while (matcher.find()) {
            addLiteral(segments, surface, annotated.substring(cursor, matcher.start()));
            String word = matcher.group(1);
            String reading = matcher.group(2);
            segments.add(new Segment(word, List.of(reading), mixedForms(word, reading)));
            surface.append(word);
            cursor = matcher.end();
            foundAnnotation = true;
        }
        addLiteral(segments, surface, annotated.substring(cursor));
        return foundAnnotation ? new ParsedSentence(surface.toString(), segments) : null;
    }

    private static void addLiteral(List<Segment> segments, StringBuilder surface, String literal) {
        String text = withoutSpaces(literal);
        if (text.isEmpty()) return;
        segments.add(new Segment(text, List.of()));
        surface.append(text);
    }

    private static String withoutSpaces(String value) {
        return value == null ? "" : value.codePoints().filter(cp -> !Character.isWhitespace(cp))
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
    }

    private static boolean isKanji(int codePoint) {
        return Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN
                || codePoint == 0x3005 || codePoint == 0x3006 || codePoint == 0x30F5 || codePoint == 0x30F6;
    }

    /**
     * Writings that keep some kanji of the word and spell the rest in kana (祖ぼ, そ母, 近く→ちかく stays whole).
     * The approved reading is split across the kanji with their dictionary readings, allowing rendaku (ほ→ぼ)
     * and gemination (がく→がっ); only a single unambiguous split is used, so no guessed boundary is accepted.
     */
    private static List<String> mixedForms(String word, String reading) {
        int[] characters = word.codePoints().toArray();
        long kanji = java.util.Arrays.stream(characters).filter(JapaneseAnswerMatcher::isKanji).count();
        String kana = normalizeKana(reading);
        if (kanji == 0 || kanji > MAX_MIXED_KANJI || kana.isEmpty() || !isUsableReading(kana)) return List.of();
        Set<List<String>> splits = new LinkedHashSet<>();
        split(characters, 0, kana, 0, new ArrayList<>(), splits);
        if (splits.size() != 1) return List.of();
        List<String> pieces = splits.iterator().next();
        LinkedHashSet<String> forms = new LinkedHashSet<>();
        for (int mask = 0; mask < 1 << characters.length; mask++) {
            StringBuilder form = new StringBuilder();
            for (int index = 0; index < characters.length; index++) {
                boolean spell = (mask & (1 << index)) != 0;
                if (spell) form.append(pieces.get(index));
                else form.appendCodePoint(characters[index]);
            }
            forms.add(form.toString());
        }
        return List.copyOf(forms);
    }

    private static void split(int[] characters, int index, String kana, int position,
                              List<String> pieces, Set<List<String>> splits) {
        if (splits.size() > 1) return;
        if (index == characters.length) {
            if (position == kana.length()) splits.add(List.copyOf(pieces));
            return;
        }
        int character = characters[index];
        List<String> candidates = new ArrayList<>();
        if (!isKanji(character)) {
            candidates.add(normalizeKana(new String(Character.toChars(character))));
        } else {
            int source = character == 0x3005 && index > 0 ? characters[index - 1] : character;
            // A kanji without dictionary readings (or a special reading such as 明日=あす) is never split by guessing.
            KanjiReadings.of(source).forEach(value -> candidates.addAll(soundChanges(value)));
        }
        for (String candidate : new LinkedHashSet<>(candidates)) {
            if (candidate.isEmpty() || !kana.startsWith(candidate, position)) continue;
            pieces.add(candidate);
            split(characters, index + 1, kana, position + candidate.length(), pieces, splits);
            pieces.removeLast();
        }
    }

    private static final String UNVOICED = "かきくけこさしすせそたちつてとはひふへほ";
    private static final String VOICED = "がぎぐげござじずぜぞだぢづでどばびぶべぼ";
    private static final String SEMI_VOICED = "ぱぴぷぺぽ";

    /** A kanji reading as it can sound inside a compound: 本来, rendaku (ほ→ぼ/ぽ) and gemination (がく→がっ). */
    private static List<String> soundChanges(String reading) {
        LinkedHashSet<String> starts = new LinkedHashSet<>();
        starts.add(reading);
        int first = UNVOICED.indexOf(reading.charAt(0));
        if (first >= 0) {
            starts.add(VOICED.charAt(first) + reading.substring(1));
            if (first >= 15) starts.add(SEMI_VOICED.charAt(first - 15) + reading.substring(1));
            if (reading.charAt(0) == 'ち') starts.add("じ" + reading.substring(1));
            if (reading.charAt(0) == 'つ') starts.add("ず" + reading.substring(1));
        }
        LinkedHashSet<String> result = new LinkedHashSet<>(starts);
        for (String value : starts) {
            if (value.length() >= 2 && "くきつち".indexOf(value.charAt(value.length() - 1)) >= 0) {
                result.add(value.substring(0, value.length() - 1) + "っ");
            }
        }
        return List.copyOf(result);
    }

    private static List<Segment> singleSegment(String expected, String reading) {
        List<String> readings = isUsableReading(reading) ? List.of(reading) : List.of();
        return List.of(new Segment(expected == null ? "" : expected, readings));
    }

    private static boolean isUsableReading(String reading) {
        return reading != null && !normalize(reading).isBlank()
                && normalize(reading).codePoints()
                .noneMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN);
    }

    private static boolean matchesSegments(List<Segment> segments, String actual) {
        return matchesSegments(segments, actual, false);
    }

    private static boolean matchesSegments(List<Segment> segments, String actual, boolean romaji) {
        int[] actualCodePoints = actual.codePoints().toArray();
        Set<Integer> positions = new LinkedHashSet<>();
        positions.add(0);
        for (Segment segment : segments) {
            if (segment.options().isEmpty()) continue;
            Set<Integer> next = new LinkedHashSet<>();
            for (String option : segment.options()) {
                int[] optionCodePoints = option.codePoints().toArray();
                for (int position : positions) {
                    if (romaji ? startsWithRomaji(actualCodePoints, position, optionCodePoints)
                            : startsWith(actualCodePoints, position, optionCodePoints)) {
                        next.add(position + optionCodePoints.length);
                    }
                }
            }
            if (next.isEmpty()) return false;
            positions = next;
        }
        return positions.contains(actualCodePoints.length);
    }

    private static boolean startsWithRomaji(int[] value, int offset, int[] prefix) {
        if (offset + prefix.length > value.length) return false;
        // Normalize long vowels inside the same approved segment, never across a particle/word boundary.
        return romajiKey(new String(prefix, 0, prefix.length))
                .equals(romajiKey(new String(value, offset, prefix.length)));
    }

    private static boolean startsWith(int[] value, int offset, int[] prefix) {
        if (offset + prefix.length > value.length) return false;
        for (int index = 0; index < prefix.length; index++) {
            if (value[offset + index] != prefix[index]) return false;
        }
        return true;
    }

    private static int similarityScore(List<Segment> segments, String actual) {
        int[] actualCodePoints = actual.codePoints().toArray();
        int[] row = new int[actualCodePoints.length + 1];
        for (int index = 0; index < row.length; index++) row[index] = index;
        int expectedLength = 0;

        for (Segment segment : segments) {
            int[] best = null;
            int shortestOption = Integer.MAX_VALUE;
            for (String option : segment.options()) {
                int[] optionCodePoints = option.codePoints().toArray();
                shortestOption = Math.min(shortestOption, optionCodePoints.length);
                int[] candidate = appendLevenshtein(row, optionCodePoints, actualCodePoints);
                if (best == null) {
                    best = candidate;
                } else {
                    for (int index = 0; index < best.length; index++) {
                        best[index] = Math.min(best[index], candidate[index]);
                    }
                }
            }
            row = best == null ? row : best;
            expectedLength += shortestOption == Integer.MAX_VALUE ? 0 : shortestOption;
        }

        int length = Math.max(1, Math.max(expectedLength, actualCodePoints.length));
        return clampScore((int) Math.round((1.0 - row[actualCodePoints.length] / (double) length) * 100));
    }

    private static int[] appendLevenshtein(int[] startingRow, int[] option, int[] actual) {
        int[] previous = startingRow.clone();
        for (int expected : option) {
            int[] current = new int[actual.length + 1];
            current[0] = previous[0] + 1;
            for (int index = 1; index <= actual.length; index++) {
                int replaceCost = expected == actual[index - 1] ? 0 : 1;
                current[index] = Math.min(current[index - 1] + 1,
                        Math.min(previous[index] + 1, previous[index - 1] + replaceCost));
            }
            previous = current;
        }
        return previous;
    }

    private static int nthIndexOf(String text, String needle, int occurrence) {
        if (text == null || needle == null || needle.isEmpty()) return -1;
        int offset = 0;
        for (int current = 1; current <= Math.max(1, occurrence); current++) {
            offset = text.indexOf(needle, offset);
            if (offset < 0) return -1;
            if (current == occurrence) return offset;
            offset += needle.length();
        }
        return -1;
    }

    private static String normalizeKana(String value) {
        return normalize(value).codePoints()
                .map(JapaneseAnswerMatcher::katakanaToHiragana)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString();
    }

    private static int katakanaToHiragana(int codePoint) {
        if ((codePoint >= 0x30A1 && codePoint <= 0x30F6)
                || codePoint == 0x30FD || codePoint == 0x30FE) {
            return codePoint - 0x60;
        }
        return codePoint;
    }

    private static boolean isIgnored(int codePoint) {
        int type = Character.getType(codePoint);
        return Character.isWhitespace(codePoint) || type == Character.CONNECTOR_PUNCTUATION
                || type == Character.DASH_PUNCTUATION || type == Character.START_PUNCTUATION
                || type == Character.END_PUNCTUATION || type == Character.INITIAL_QUOTE_PUNCTUATION
                || type == Character.FINAL_QUOTE_PUNCTUATION || type == Character.OTHER_PUNCTUATION;
    }

    private static int clampScore(int value) {
        return Math.max(0, Math.min(100, value));
    }

    enum MatchKind {
        EXACT_SURFACE,
        EQUIVALENT,
        NONE
    }

    record MatchResult(MatchKind kind, String normalizedExpected, String normalizedActual, int score,
                       List<FeedbackPart> feedback) {
        boolean isCorrect() {
            return kind != MatchKind.NONE;
        }

        boolean usesEquivalentWriting() {
            return kind == MatchKind.EQUIVALENT;
        }
    }

    record FeedbackPart(boolean matched, String text) {
    }

    private record FeedbackOption(int span, int[] value, boolean romaji) {
        boolean matches(int[] actual, int offset) {
            return romaji ? startsWithRomaji(actual, offset, value) : startsWith(actual, offset, value);
        }
    }

    private record ParsedSentence(String surface, List<Segment> segments) {
    }

    /** {@code mixed}: partly-kana writings of the word, accepted as answers but never shown as feedback readings. */
    private record Segment(String surface, List<String> readings, List<String> mixed) {
        Segment(String surface, List<String> readings) {
            this(surface, readings, List.of());
        }

        List<String> options() {
            LinkedHashSet<String> result = new LinkedHashSet<>();
            String normalizedSurface = normalizeKana(surface);
            if (!normalizedSurface.isEmpty()) result.add(normalizedSurface);
            readings.stream().map(JapaneseAnswerMatcher::normalizeKana)
                    .filter(value -> !value.isEmpty()).forEach(result::add);
            mixed.stream().map(JapaneseAnswerMatcher::normalizeKana)
                    .filter(value -> !value.isEmpty()).forEach(result::add);
            return List.copyOf(result);
        }
    }
}
