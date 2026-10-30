import com.jlas.dictionary.util.VerbDeinflector;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Xuất danh sách duyệt: mục từ không có từ loại mà chỉ là dạng chia của động từ/tính từ đuôi い đã
 * có trong từ điển (貸します -> 貸す), hoặc là câu/cụm lịch sự (いくらですか). Chỉ đọc, không sửa DB.
 *
 * <p>Chạy sau {@code mvn compile} (xem README.md cùng thư mục):
 * {@code java -cp target/classes scripts/dictionary_review/FindInflectedEntries.java entries.tsv out.csv}.
 * entries.tsv lấy từ export_entries.sql: id, expression, normalized_expression, reading,
 * normalized_reading, meaning, pos.</p>
 */
public class FindInflectedEntries {
    record Entry(long id, String expression, String normExpr, String reading, String normReading,
                 String meaning, Set<String> pos) { }

    static final Pattern KANJI = Pattern.compile("[\\x{3400}-\\x{4DBF}\\x{4E00}-\\x{9FFF}\\x{F900}-\\x{FAFF}\\x{20000}-\\x{323AF}]");
    // Bỏ ます trơn: tên cá viết kana (からふとます = 鱒) và động từ thể từ điển (なやます) cũng tận cùng bằng ます.
    static final Pattern SENTENCE = Pattern.compile(
            ".*(ました|ません|ませんでした|ましょう|ましょうか|ますか|ませんか|します|ございます|致します|いたします|"
            + "です|でした|ですか|でしょう|でしょうか|ください|下さい|[?？。])$");
    /** Đuôi chia rõ ràng (nhóm "Dạng chia"); phần còn lại là thể khả năng/bị động/sai khiến. */
    static final Pattern CONJUGATED = Pattern.compile(
            ".*(ます|ました|ません|ませんでした|ましょう|たい|たくない|たかった|ない|なかった|ている|てる|ていた|でいる|"
            + "た|だ|て|で|たら|だら|たり|だり|ば|よう|こう|ごう|そう|とう|のう|ぼう|もう|ろう)$");
    static final Pattern POLITE = Pattern.compile(".*(ます|ました|ません|ませんでした|ましょう)$");
    static final String U_ROW = "うくぐすつぬぶむる";
    static final String I_ROW = "いきぎしちにびみり";

    public static void main(String[] args) throws Exception {
        List<Entry> entries = new ArrayList<>();
        for (String line : Files.readAllLines(Path.of(args[0]), StandardCharsets.UTF_8)) {
            String[] f = line.split("\t", -1);
            Set<String> pos = f[6].isEmpty() ? Set.of() : Set.of(f[6].split(","));
            entries.add(new Entry(Long.parseLong(f[0]), f[1], f[2], f[3], f[4], f[5], pos));
        }
        Map<String, List<Entry>> byExpr = new HashMap<>();
        Map<String, List<Entry>> byReading = new HashMap<>();
        for (Entry e : entries) {
            byExpr.computeIfAbsent(e.normExpr, k -> new ArrayList<>()).add(e);
            byReading.computeIfAbsent(e.normReading, k -> new ArrayList<>()).add(e);
        }

        int inflected = 0, sentences = 0;
        try (BufferedWriter out = Files.newBufferedWriter(Path.of(args[1]), StandardCharsets.UTF_8)) {
            out.write('﻿'); // BOM để Excel đọc đúng UTF-8
            out.write("id,tu,cach_doc,nghia,nhom,the_tu_dien_da_co,id_the_tu_dien,de_xuat\n");
            for (Entry e : entries) {
                if (!e.pos.isEmpty()) continue;
                // 休学する đã là thể từ điển; mục chỉ có kana khớp cách đọc quá lỏng (うんでい -> 生む).
                boolean hasKanji = KANJI.matcher(e.normExpr).find();
                Entry base = hasKanji && !e.normExpr.endsWith("する") ? findBase(e, byExpr, byReading) : null;
                if (base != null && isStemNoun(e.normExpr, base.normExpr)) base = null;
                if (base != null) {
                    inflected++;
                    boolean conjugated = CONJUGATED.matcher(e.normExpr).matches();
                    // Chỉ dạng ます chắc chắn không mang nghĩa riêng; 傑出した ("nổi bật") thì có.
                    boolean polite = POLITE.matcher(e.normExpr).matches();
                    write(out, e, conjugated ? "Dạng chia của từ đã có" : "Thể khả năng/bị động/sai khiến",
                            base.expression + "【" + base.reading + "】", String.valueOf(base.id),
                            polite ? "Xoá (tìm kiếm đã tự ra " + base.expression + ")" : "Xem lại");
                } else if (SENTENCE.matcher(e.normExpr).matches() && e.normExpr.codePointCount(0, e.normExpr.length()) > 2) {
                    sentences++;
                    write(out, e, "Câu / cụm lịch sự", "", "", "Xem lại");
                }
            }
        }
        System.out.println("inflected=" + inflected + " sentences=" + sentences);
    }

    /** Thể từ điển đã có trong từ điển, với từ loại khớp và (nếu có Kanji) cách đọc cũng chia ngược khớp. */
    static Entry findBase(Entry e, Map<String, List<Entry>> byExpr, Map<String, List<Entry>> byReading) {
        boolean hasKanji = KANJI.matcher(e.normExpr).find();
        Set<String> readingBases = new HashSet<>();
        if (hasKanji) {
            for (VerbDeinflector.Candidate c : VerbDeinflector.deinflect(e.normReading)) readingBases.add(c.term());
        }
        for (VerbDeinflector.Candidate c : VerbDeinflector.deinflect(hasKanji ? e.normExpr : e.normReading)) {
            List<Entry> matches = (hasKanji ? byExpr : byReading).getOrDefault(c.term(), List.of());
            for (Entry m : matches) {
                if (m.id == e.id) continue;
                if (hasKanji && !readingBases.contains(m.normReading)) continue;
                boolean posOk = m.pos.stream().anyMatch(p -> c.forms().stream().anyMatch(f -> f.matchesPartOfSpeech(p)));
                if (posOk) return m;
            }
        }
        return null;
    }

    /** 積み込み / 押し切り: danh từ tạo từ dạng liên dụng, giống 行き, không phải lỗi. */
    static boolean isStemNoun(String expression, String base) {
        if (base.endsWith("る") && expression.equals(base.substring(0, base.length() - 1))) return true;
        int u = U_ROW.indexOf(base.charAt(base.length() - 1));
        return u >= 0 && expression.equals(base.substring(0, base.length() - 1) + I_ROW.charAt(u));
    }

    static void write(BufferedWriter out, Entry e, String group, String base, String baseId, String advice) throws Exception {
        out.write(String.join(",", String.valueOf(e.id), csv(e.expression), csv(e.reading), csv(e.meaning),
                csv(group), csv(base), baseId, csv(advice)));
        out.write("\n");
    }

    static String csv(String v) {
        return "\"" + v.replace("\"", "\"\"") + "\"";
    }
}
