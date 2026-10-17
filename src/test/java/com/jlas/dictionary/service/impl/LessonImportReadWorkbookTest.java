package com.jlas.dictionary.service.impl;

import com.jlas.common.exception.ApiException;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [JLAS2-17] Đọc file Excel nhập bài học: khoan dung với tên sheet / vị trí dòng tiêu đề / thứ tự cột / ô công thức,
 * và báo lỗi nói rõ sheet, cột thiếu, sheet không nhận ra.
 */
class LessonImportReadWorkbookTest {

    private static byte[] workbook(Consumer<XSSFWorkbook> build) throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            build.accept(wb);
            wb.write(out);
            return out.toByteArray();
        }
    }

    private static void row(Sheet sheet, int index, Object... values) {
        Row r = sheet.createRow(index);
        for (int i = 0; i < values.length; i++) {
            if (values[i] == null) continue;
            String v = values[i].toString();
            if (v.startsWith("=")) r.createCell(i).setCellFormula(v.substring(1));
            else r.createCell(i).setCellValue(v);
        }
    }

    private static final Object[] VOCAB_HEADER = {"Từ *", "Cách đọc *", "Romaji", "Nghĩa *", "Loại từ", "JLPT"};

    @Test
    void vietnameseSheetNamesAreRecognised() throws IOException {
        LessonImportServiceImpl.WorkbookData data = LessonImportServiceImpl.readWorkbook(workbook(wb -> {
            Sheet v = wb.createSheet("Từ vựng");
            row(v, 0, VOCAB_HEADER);
            row(v, 1, "会議", "かいぎ", "kaigi", "cuộc họp", "danh từ", "N4");
            Sheet g = wb.createSheet(" ngữ PHÁP ");
            row(g, 0, "Mẫu ngữ pháp *", "Nghĩa *");
            row(g, 1, "～ていただけますか", "nhờ lịch sự");
            Sheet d = wb.createSheet("Hội thoại");
            row(d, 0, "Đoạn hội thoại *", "Mã vai *", "Tên vai (tiếng Việt) *", "Tên vai (tiếng Nhật)", "Lời thoại tiếng Nhật *");
            row(d, 1, "Gặp đối tác", "A", "Anh A", null, "はじめまして");
            Sheet e = wb.createSheet("Ví dụ");
            row(e, 0, "Mẫu ngữ pháp *", "Câu tiếng Nhật *");
            row(e, 1, "～ていただけますか", "資料を送っていただけますか。");
        }));
        assertTrue(data.headerErrors().isEmpty(), data.headerErrors().toString());
        assertEquals("会議", data.rows().get("TuVung").get(0).get(0));
        assertEquals("cuộc họp", data.rows().get("TuVung").get(0).get(3));
        assertEquals(1, data.rows().get("NguPhap").size());
        assertEquals(1, data.rows().get("HoiThoai").size());
        assertEquals(1, data.rows().get("ViDu").size());
        assertEquals("Từ vựng", data.sheetTitles().get("TuVung"));
    }

    @Test
    void headerFoundBelowTitleRowsAndColumnsMatchedByName() throws IOException {
        LessonImportServiceImpl.WorkbookData data = LessonImportServiceImpl.readWorkbook(workbook(wb -> {
            Sheet v = wb.createSheet("TuVung");
            row(v, 0, "Từ vựng chủ đề Business");                 // dòng tiêu đề lớn
            row(v, 2, "Nghĩa", "Từ", "Ghi chú riêng", "Cách đọc");  // tiêu đề ở dòng 3, đảo thứ tự, không dấu *, có cột lạ
            row(v, 3, "hợp đồng", "契約", "xem lại", "けいやく");
            row(v, 4);                                              // dòng trống
            row(v, 5, "báo cáo", "報告", null, "ほうこく");
        }));
        assertTrue(data.headerErrors().isEmpty(), data.headerErrors().toString());
        List<LessonImportServiceImpl.SheetRow> rows = data.rows().get("TuVung");
        assertEquals(2, rows.size());
        assertEquals(4, rows.get(0).number());                     // số dòng như Excel hiển thị
        assertEquals("契約", rows.get(0).get(0));
        assertEquals("けいやく", rows.get(0).get(1));
        assertEquals("hợp đồng", rows.get(0).get(3));
        assertEquals(6, rows.get(1).number());
    }

    @Test
    void formulaCellsGiveTheirComputedValue() throws IOException {
        LessonImportServiceImpl.WorkbookData data = LessonImportServiceImpl.readWorkbook(workbook(wb -> {
            Sheet v = wb.createSheet("TuVung");
            row(v, 0, VOCAB_HEADER);
            row(v, 1, "=\"会\"&\"議\"", "かいぎ", null, "=CONCATENATE(\"cuộc \",\"họp\")");
        }));
        assertEquals("会議", data.rows().get("TuVung").get(0).get(0));
        assertEquals("cuộc họp", data.rows().get("TuVung").get(0).get(3));
    }

    @Test
    void headerOnlyFileExplainsWhatIsEmptyAndWhichSheetsAreUnknown() throws IOException {
        LessonImportServiceImpl.WorkbookData data = LessonImportServiceImpl.readWorkbook(workbook(wb -> {
            row(wb.createSheet("TuVung"), 0, VOCAB_HEADER);
            row(wb.createSheet("Kanji"), 0, "Chữ Kanji *");
            row(wb.createSheet("Business words"), 0, "word", "meaning");
            row(wb.createSheet("HuongDan"), 0, "Hướng dẫn");
        }));
        assertTrue(data.rows().values().stream().allMatch(List::isEmpty));
        String message = LessonImportServiceImpl.emptyFileMessage(data);
        assertTrue(message.contains("chỉ có dòng tiêu đề"), message);
        assertTrue(message.contains("TuVung") && message.contains("Kanji"), message);
        assertTrue(message.contains("Business words"), message);
        assertTrue(!message.contains("HuongDan"), message);
    }

    @Test
    void missingRequiredColumnIsReportedWithSheetAndColumnName() throws IOException {
        LessonImportServiceImpl.WorkbookData data = LessonImportServiceImpl.readWorkbook(workbook(wb -> {
            Sheet v = wb.createSheet("Từ vựng");
            row(v, 0, "Từ", "Cách đọc", "Ý nghĩa");
            row(v, 1, "会議", "かいぎ", "cuộc họp");
        }));
        assertEquals(1, data.headerErrors().size());
        String message = data.headerErrors().get(0).getMessage();
        assertTrue(message.contains("Sheet 'Từ vựng' thiếu cột 'Nghĩa'"), message);
        assertTrue(message.contains("Ý nghĩa"), message);
    }

    @Test
    void dataWithoutHeaderIsReported() throws IOException {
        LessonImportServiceImpl.WorkbookData data = LessonImportServiceImpl.readWorkbook(workbook(wb -> {
            Sheet v = wb.createSheet("TuVung");
            row(v, 0, "会議", "かいぎ", "kaigi", "cuộc họp");
        }));
        assertEquals(1, data.headerErrors().size());
        assertTrue(data.headerErrors().get(0).getMessage().contains("không có dòng tiêu đề"));
    }

    @Test
    void notAnExcelFileIsAFriendlyError() {
        ApiException e = assertThrows(ApiException.class, () -> LessonImportServiceImpl.readWorkbook("not excel".getBytes()));
        assertTrue(e.getMessage().contains("Không đọc được file Excel"));
    }
}
