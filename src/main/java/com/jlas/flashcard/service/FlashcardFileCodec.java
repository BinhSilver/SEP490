package com.jlas.flashcard.service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Reads and writes flashcard files. Every format uses the card's four fields as columns
 * (Kanji, Cách đọc, Nghĩa, Hán Việt), so an exported file can be imported again unchanged.
 * Older three-column files (Mặt trước, Mặt sau, Hán Việt) are still read; the editor import
 * tells the two layouts apart.
 */
@Component
public class FlashcardFileCodec {
    public static final List<String> HEADER = List.of("Kanji", "Cách đọc", "Nghĩa", "Hán Việt");
    private static final int COLUMNS = HEADER.size();
    public static final long MAX_IMPORT_BYTES = 1024 * 1024;
    public static final int MAX_IMPORT_ROWS = 2000;

    public record CardRow(String front, String reading, String meaning, String hanViet) {}

    public enum Format {
        CSV("csv", "text/csv;charset=UTF-8"),
        TXT("txt", "text/plain;charset=UTF-8"),
        XLSX("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

        public final String extension;
        public final String contentType;

        Format(String extension, String contentType) { this.extension = extension; this.contentType = contentType; }

        public static Format of(String value) {
            if (value != null) for (Format format : values()) if (format.extension.equalsIgnoreCase(value.trim())) return format;
            if ("tsv".equalsIgnoreCase(value)) return TXT;
            throw bad("Định dạng không hỗ trợ. Dùng CSV, TXT hoặc XLSX");
        }

        public static Format ofFilename(String filename) {
            int dot = filename == null ? -1 : filename.lastIndexOf('.');
            return of(dot < 0 ? null : filename.substring(dot + 1));
        }
    }

    public byte[] write(List<CardRow> rows, Format format) { return write(rows, format, true); }

    /** Without Hán Việt the file has three columns (Kanji, Cách đọc, Nghĩa), which the editor import also accepts. */
    public byte[] write(List<CardRow> rows, Format format, boolean includeHanViet) {
        List<String> header = includeHanViet ? HEADER : HEADER.subList(0, COLUMNS - 1);
        return switch (format) {
            case CSV -> {
                // BOM so Excel opens the UTF-8 file with Japanese/Vietnamese text intact.
                StringBuilder out = new StringBuilder("\uFEFF").append(csvLine(header));
                rows.forEach(row -> out.append(csvLine(cells(row, includeHanViet))));
                yield out.toString().getBytes(StandardCharsets.UTF_8);
            }
            case TXT -> {
                StringBuilder out = new StringBuilder();
                rows.forEach(row -> out.append(String.join("\t", cells(row, includeHanViet).stream().map(FlashcardFileCodec::flatten).toList()).replaceAll("\t+$", "")).append('\n'));
                yield out.toString().getBytes(StandardCharsets.UTF_8);
            }
            case XLSX -> xlsx(rows, header, includeHanViet);
        };
    }

    /**
     * Converts an uploaded file into editor import text: one tab-separated line per card with the
     * file's columns as-is (Kanji, Cách đọc, Nghĩa, Hán Việt, or the older Mặt trước, Mặt sau, Hán Việt).
     * TXT is passed through so the "Kanji - Hiragana : Nghĩa (Hán Việt)" syntax keeps working.
     */
    public String toImportText(String filename, byte[] content) {
        if (content == null || content.length == 0) throw bad("File trống");
        if (content.length > MAX_IMPORT_BYTES) throw bad("File không được vượt quá 1MB");
        Format format = Format.ofFilename(filename);
        String text = format == Format.XLSX ? null : stripBom(new String(content, StandardCharsets.UTF_8));
        if (format == Format.TXT) {
            if (text.lines().count() > MAX_IMPORT_ROWS) throw tooManyRows();
            return text.replace("\r\n", "\n").trim();
        }
        List<List<String>> rows = format == Format.CSV ? parseCsv(text) : readXlsx(content);
        if (!rows.isEmpty() && isHeader(rows.get(0))) rows = rows.subList(1, rows.size());
        if (rows.size() > MAX_IMPORT_ROWS) throw tooManyRows();
        List<String> lines = new ArrayList<>();
        for (List<String> row : rows) {
            List<String> cells = new ArrayList<>();
            for (int i = 0; i < COLUMNS; i++) cells.add(i < row.size() ? flatten(unguard(row.get(i))) : "");
            if (cells.stream().allMatch(String::isBlank)) continue;
            while (!cells.isEmpty() && cells.get(cells.size() - 1).isBlank()) cells.remove(cells.size() - 1);
            lines.add(String.join("\t", cells));
        }
        return String.join("\n", lines);
    }

    private static List<String> cells(CardRow row, boolean includeHanViet) {
        return includeHanViet
                ? List.of(nz(row.front()), nz(row.reading()), nz(row.meaning()), nz(row.hanViet()))
                : List.of(nz(row.front()), nz(row.reading()), nz(row.meaning()));
    }

    private static String csvLine(List<String> cells) {
        return String.join(",", cells.stream().map(FlashcardFileCodec::csvCell).toList()) + "\r\n";
    }

    /** Quotes a cell when needed and neutralises spreadsheet formula prefixes (CSV injection). */
    static String csvCell(String value) {
        String cell = isFormulaLike(value) ? "'" + value : value;
        boolean quote = !cell.equals(value) || cell.contains(",") || cell.contains("\"") || cell.contains("\n") || cell.contains("\r");
        return quote ? "\"" + cell.replace("\"", "\"\"") + "\"" : cell;
    }

    static List<List<String>> parseCsv(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') { cell.append('"'); i++; }
                else if (c == '"') quoted = false;
                else cell.append(c);
            } else if (c == '"') quoted = true;
            else if (c == ',') { row.add(cell.toString()); cell.setLength(0); }
            else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                row.add(cell.toString()); cell.setLength(0); rows.add(row); row = new ArrayList<>();
                if (rows.size() > MAX_IMPORT_ROWS + 1) throw tooManyRows();
            } else cell.append(c);
        }
        if (cell.length() > 0 || !row.isEmpty()) { row.add(cell.toString()); rows.add(row); }
        return rows;
    }

    private static byte[] xlsx(List<CardRow> rows, List<String> header, boolean includeHanViet) {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Flashcards");
            CellStyle headerStyle = workbook.createCellStyle();
            Font bold = workbook.createFont();
            bold.setBold(true);
            headerStyle.setFont(bold);
            writeRow(sheet.createRow(0), header, headerStyle);
            for (int i = 0; i < rows.size(); i++) writeRow(sheet.createRow(i + 1), cells(rows.get(i), includeHanViet), null);
            sheet.setColumnWidth(0, 20 * 256);
            sheet.setColumnWidth(1, 20 * 256);
            sheet.setColumnWidth(2, 40 * 256);
            if (includeHanViet) sheet.setColumnWidth(3, 20 * 256);
            sheet.createFreezePane(0, 1);
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Không thể tạo file Excel", exception);
        }
    }

    /** String cells are never evaluated by Excel, so no formula guard is needed here. */
    private static void writeRow(Row row, List<String> values, CellStyle style) {
        for (int i = 0; i < values.size(); i++) {
            Cell cell = row.createCell(i);
            cell.setCellValue(values.get(i));
            if (style != null) cell.setCellStyle(style);
        }
    }

    private static List<List<String>> readXlsx(byte[] content) {
        DataFormatter formatter = new DataFormatter();
        List<List<String>> rows = new ArrayList<>();
        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(content))) {
            if (workbook.getNumberOfSheets() == 0) return rows;
            Sheet sheet = workbook.getSheetAt(0);
            if (sheet.getLastRowNum() > MAX_IMPORT_ROWS) throw tooManyRows();
            for (int r = sheet.getFirstRowNum(); r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                List<String> cells = new ArrayList<>();
                // Formula cells are read as their text, never evaluated.
                for (int c = 0; c < COLUMNS; c++) cells.add(row == null ? "" : formatter.formatCellValue(row.getCell(c)));
                rows.add(cells);
            }
            return rows;
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof ResponseStatusException status) throw status;
            throw bad("Không đọc được file Excel (.xlsx)");
        }
    }

    private static boolean isHeader(List<String> row) {
        String first = row.isEmpty() ? "" : row.get(0).trim().toLowerCase(Locale.ROOT);
        return first.equals("mặt trước") || first.equals("front") || first.equals("kanji") || first.equals("thuật ngữ");
    }

    private static boolean isFormulaLike(String value) {
        return !value.isEmpty() && "=+-@".indexOf(value.charAt(0)) >= 0;
    }

    /** Removes the apostrophe that {@link #csvCell} adds in front of formula-like cells. */
    private static String unguard(String value) {
        return value != null && value.length() > 1 && value.charAt(0) == '\'' && isFormulaLike(value.substring(1)) ? value.substring(1) : nz(value);
    }

    private static String flatten(String value) { return nz(value).replaceAll("[\\t\\r\\n]+", " ").trim(); }
    private static String stripBom(String text) { return text.startsWith("\uFEFF") ? text.substring(1) : text; }
    private static String nz(String value) { return value == null ? "" : value; }
    private static ResponseStatusException tooManyRows() { return bad("File có quá nhiều dòng (tối đa " + MAX_IMPORT_ROWS + ")"); }
    private static ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
}
