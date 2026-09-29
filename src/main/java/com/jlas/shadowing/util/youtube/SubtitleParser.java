package com.jlas.shadowing.util.youtube;

import com.jlas.shadowing.dto.SubtitleDto;
import com.jlas.shadowing.util.SubtitleSegmenter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SubtitleParser {

    /** 00:00:01.000 --> 00:00:02.500  or  with comma (SRT style in VTT) */
    private static final Pattern TIME_FULL = Pattern.compile(
            "(\\d{1,2}):(\\d{2}):(\\d{2})[.,](\\d{1,3})\\s*-->\\s*(\\d{1,2}):(\\d{2}):(\\d{2})[.,](\\d{1,3}).*");

    /** 00:01.000 --> 00:02.500  (no hours — some YouTube/auto exports) */
    private static final Pattern TIME_SHORT = Pattern.compile(
            "(\\d{1,2}):(\\d{2})[.,](\\d{1,3})\\s*-->\\s*(\\d{1,2}):(\\d{2})[.,](\\d{1,3}).*");

    private SubtitleParser() { }

    public static List<SubtitleDto> parseVtt(Path vttFile) throws IOException {
        String content = Files.readString(vttFile, StandardCharsets.UTF_8);
        // strip UTF-8 BOM if present
        if (!content.isEmpty() && content.charAt(0) == '\uFEFF') {
            content = content.substring(1);
        }
        List<SubtitleDto> parsed = parseVttContent(content);
        if (parsed.isEmpty() && content.toLowerCase().contains("-->")) {
            parsed = parseVttContent(content.replace('\u00A0', ' '));
        }
        return parsed;
    }

    public static List<SubtitleDto> parseVttContent(String content) {
        List<SubtitleDto> result = new ArrayList<>();
        if (content == null || content.isBlank()) return result;

        String[] lines = content.replace("\r\n", "\n").replace('\r', '\n').split("\n");

        int i = 0;
        while (i < lines.length) {
            String line = lines[i].trim();
            CueTiming timing = parseTiming(line);
            if (timing != null) {
                double start = timing.start;
                double end = timing.end;
                i++;
                StringBuilder text = new StringBuilder();
                while (i < lines.length) {
                    String part = lines[i].trim();
                    if (part.isEmpty()) break;
                    if (parseTiming(part) != null) break;
                    if (part.matches("\\d+") && text.length() == 0) {
                        i++;
                        continue;
                    }
                    if (part.startsWith("NOTE") || part.startsWith("STYLE") || part.startsWith("REGION")) {
                        i++;
                        continue;
                    }
                    if (text.length() > 0) text.append(' ');
                    text.append(cleanCueText(part));
                    i++;
                }
                String cue = text.toString().trim();
                if (!cue.isEmpty() && end > start) {
                    SubtitleDto dto = new SubtitleDto();
                    dto.setStartTime(start);
                    dto.setEndTime(end);
                    dto.setText(cue);
                    result.add(dto);
                }
                continue;
            }
            i++;
        }
        // 1) short adjacent merges  2) collapse YouTube rolling auto-captions
        return SubtitleSegmenter.splitLongCues(collapseRollingCaptions(mergeAdjacent(result)));
    }

    private static CueTiming parseTiming(String line) {
        if (line == null || !line.contains("-->")) return null;
        Matcher full = TIME_FULL.matcher(line);
        if (full.matches()) {
            return new CueTiming(
                    toSeconds(full.group(1), full.group(2), full.group(3), full.group(4)),
                    toSeconds(full.group(5), full.group(6), full.group(7), full.group(8))
            );
        }
        Matcher shortT = TIME_SHORT.matcher(line);
        if (shortT.matches()) {
            return new CueTiming(
                    toSeconds("0", shortT.group(1), shortT.group(2), shortT.group(3)),
                    toSeconds("0", shortT.group(4), shortT.group(5), shortT.group(6))
            );
        }
        return null;
    }

    private static List<SubtitleDto> mergeAdjacent(List<SubtitleDto> cues) {
        if (cues.size() <= 1) return cues;
        List<SubtitleDto> merged = new ArrayList<>();
        SubtitleDto current = cues.get(0);
        for (int i = 1; i < cues.size(); i++) {
            SubtitleDto next = cues.get(i);
            boolean sameTiming = Math.abs(current.getEndTime() - next.getStartTime()) < 0.05;
            boolean shortCue = current.getText().length() < 8 && sameTiming;
            if (shortCue) {
                current.setText(current.getText() + next.getText());
                current.setEndTime(next.getEndTime());
            } else {
                merged.add(current);
                current = next;
            }
        }
        merged.add(current);
        return merged;
    }

    /**
     * YouTube auto-captions often emit overlapping "rolling" windows:
     *   A
     *   A+B
     *   B
     *   B+C
     * Collapse these into stable non-duplicated cues before AI / display.
     */
    public static List<SubtitleDto> collapseRollingCaptions(List<SubtitleDto> cues) {
        if (cues == null || cues.size() <= 1) return cues;
        List<SubtitleDto> out = new ArrayList<>();
        SubtitleDto cur = copyCue(cues.get(0));

        for (int i = 1; i < cues.size(); i++) {
            SubtitleDto next = cues.get(i);
            String a = normalizeForCompare(cur.getText());
            String b = normalizeForCompare(next.getText());
            if (a.isEmpty()) {
                cur = copyCue(next);
                continue;
            }
            if (b.isEmpty()) {
                cur.setEndTime(Math.max(cur.getEndTime(), next.getEndTime()));
                continue;
            }

            // next is extension of current (rolling forward)
            if (b.startsWith(a) || b.contains(a) && b.length() > a.length()) {
                cur.setText(pickLonger(cur.getText(), next.getText()));
                cur.setEndTime(Math.max(cur.getEndTime(), next.getEndTime()));
                continue;
            }
            // current already contains next (shorter echo)
            if (a.startsWith(b) || a.contains(b)) {
                cur.setEndTime(Math.max(cur.getEndTime(), next.getEndTime()));
                continue;
            }

            // long shared prefix  rolling window shift (keep longer / newer text)
            int prefix = commonPrefixLen(a, b);
            int minLen = Math.min(a.length(), b.length());
            if (minLen >= 6 && prefix >= minLen * 0.55) {
                cur.setText(pickLonger(cur.getText(), next.getText()));
                cur.setEndTime(Math.max(cur.getEndTime(), next.getEndTime()));
                continue;
            }

            // long shared suffix (window slid from the left)
            int suffix = commonSuffixLen(a, b);
            if (minLen >= 6 && suffix >= minLen * 0.55) {
                // prefer next as the advanced window
                cur.setText(next.getText());
                cur.setEndTime(Math.max(cur.getEndTime(), next.getEndTime()));
                continue;
            }

            // sentence-boundary merge: current incomplete, next continues soon
            boolean tight = next.getStartTime() - cur.getEndTime() < 0.35
                    && next.getStartTime() >= cur.getStartTime() - 0.05;
            boolean incomplete = !endsSentence(cur.getText()) && cur.getText().length() < 80;
            if (tight && incomplete) {
                cur.setText(joinCueText(cur.getText(), next.getText()));
                cur.setEndTime(Math.max(cur.getEndTime(), next.getEndTime()));
                continue;
            }

            out.add(cur);
            cur = copyCue(next);
        }
        out.add(cur);

        // second pass: drop near-duplicate consecutive lines
        return dedupeNearDuplicates(out);
    }

    private static List<SubtitleDto> dedupeNearDuplicates(List<SubtitleDto> cues) {
        if (cues.size() <= 1) return cues;
        List<SubtitleDto> out = new ArrayList<>();
        out.add(cues.get(0));
        for (int i = 1; i < cues.size(); i++) {
            SubtitleDto prev = out.get(out.size() - 1);
            SubtitleDto cur = cues.get(i);
            String a = normalizeForCompare(prev.getText());
            String b = normalizeForCompare(cur.getText());
            if (a.equals(b) || (a.length() > 10 && (a.contains(b) || b.contains(a)))) {
                prev.setEndTime(Math.max(prev.getEndTime(), cur.getEndTime()));
                if (b.length() > a.length()) prev.setText(cur.getText());
                continue;
            }
            out.add(cur);
        }
        return out;
    }

    private static SubtitleDto copyCue(SubtitleDto src) {
        SubtitleDto d = new SubtitleDto();
        d.setStartTime(src.getStartTime());
        d.setEndTime(src.getEndTime());
        d.setText(src.getText());
        d.setFurigana(src.getFurigana());
        d.setTranslation(src.getTranslation());
        return d;
    }

    private static String normalizeForCompare(String s) {
        if (s == null) return "";
        return s.replace(" ", "").replace("\u3000", "").replace("\n", "").trim();
    }

    private static String pickLonger(String a, String b) {
        if (a == null) return b;
        if (b == null) return a;
        return b.length() >= a.length() ? b : a;
    }

    private static String joinCueText(String a, String b) {
        if (a == null || a.isBlank()) return b;
        if (b == null || b.isBlank()) return a;
        String an = normalizeForCompare(a);
        String bn = normalizeForCompare(b);
        if (bn.startsWith(an)) return b;
        if (an.endsWith(bn)) return a;
        // avoid double-joining shared overlap
        int maxOverlap = Math.min(an.length(), bn.length());
        for (int k = maxOverlap; k >= 3; k--) {
            if (an.endsWith(bn.substring(0, k))) {
                return a + b.substring(Math.min(b.length(), k));
            }
        }
        return a + b;
    }

    private static boolean endsSentence(String text) {
        if (text == null || text.isBlank()) return true;
        char c = text.charAt(text.length() - 1);
        return c == '。' || c == '！' || c == '？' || c == '.' || c == '!' || c == '?'
                || c == '…' || c == '」' || c == '』';
    }

    private static int commonPrefixLen(String a, String b) {
        int n = Math.min(a.length(), b.length());
        int i = 0;
        while (i < n && a.charAt(i) == b.charAt(i)) i++;
        return i;
    }

    private static int commonSuffixLen(String a, String b) {
        int n = Math.min(a.length(), b.length());
        int i = 0;
        while (i < n && a.charAt(a.length() - 1 - i) == b.charAt(b.length() - 1 - i)) i++;
        return i;
    }

    private static String cleanCueText(String raw) {
        return raw
                .replaceAll("<[^>]+>", "")
                .replaceAll("\\{[^}]+\\}", "")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace('\u200B', ' ')
                .trim();
    }

    private static double toSeconds(String h, String m, String s, String frac) {
        int hours = Integer.parseInt(h);
        int minutes = Integer.parseInt(m);
        int seconds = Integer.parseInt(s);
        String f = frac;
        if (f.length() == 1) f = f + "00";
        else if (f.length() == 2) f = f + "0";
        else if (f.length() > 3) f = f.substring(0, 3);
        int ms = Integer.parseInt(f);
        return hours * 3600.0 + minutes * 60.0 + seconds + ms / 1000.0;
    }

    private record CueTiming(double start, double end) { }
}
