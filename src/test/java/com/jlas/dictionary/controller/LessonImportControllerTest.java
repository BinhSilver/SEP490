package com.jlas.dictionary.controller;

import com.jlas.common.GlobalApiExceptionHandler;
import com.jlas.dictionary.dto.VocabularyCreateRequest;
import com.jlas.dictionary.entity.Lesson;
import com.jlas.dictionary.entity.Textbook;
import com.jlas.dictionary.entity.Unit;
import com.jlas.dictionary.handwriting.HandwritingRateLimiter;
import com.jlas.dictionary.repository.ContentVocabularyRepository;
import com.jlas.dictionary.repository.GrammarPointRepository;
import com.jlas.dictionary.repository.KanjiRepository;
import com.jlas.dictionary.repository.LessonKanjiRepository;
import com.jlas.dictionary.repository.LessonRepository;
import com.jlas.dictionary.repository.VocabularyEntryRepository;
import com.jlas.dictionary.service.ContentAdminService;
import com.jlas.dictionary.service.DictionaryService;
import com.jlas.dictionary.service.impl.LessonImportServiceImpl;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Hợp đồng HTTP của POST /api/dictionary/lessons/{id}/import: nhận multipart (field "file"), import theo
 * kiểu tất cả hoặc không. Service import là bản thật; repository và ContentAdminService là mock, nên test
 * không cần DB và kiểm được "không ghi gì" bằng cách khẳng định các thao tác ghi không bao giờ được gọi.
 */
class LessonImportControllerTest {

    private static final long LESSON_ID = 467L;
    private static final String URL = "/api/dictionary/lessons/" + LESSON_ID + "/import";
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private ContentAdminService contentAdminService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        LessonRepository lessonRepository = mock(LessonRepository.class);
        Textbook textbook = Textbook.builder().id(1L).status("DRAFT").build();
        Unit unit = Unit.builder().id(2L).textbook(textbook).build();
        when(lessonRepository.findWithUnitById(LESSON_ID))
                .thenReturn(Optional.of(Lesson.builder().id(LESSON_ID).unit(unit).build()));

        contentAdminService = mock(ContentAdminService.class);
        // Các repository còn lại: mock mặc định trả list rỗng / Optional.empty → bài trống, từ chưa có trong từ điển.
        LessonImportServiceImpl importService = new LessonImportServiceImpl(
                lessonRepository,
                mock(VocabularyEntryRepository.class),
                mock(ContentVocabularyRepository.class),
                mock(GrammarPointRepository.class),
                mock(KanjiRepository.class),
                mock(LessonKanjiRepository.class),
                contentAdminService);

        mvc = MockMvcBuilders.standaloneSetup(new DictionaryController(
                        mock(DictionaryService.class), mock(HandwritingRateLimiter.class),
                        mock(com.jlas.dictionary.service.DictionaryEngagementService.class),
                        contentAdminService, importService))
                .setControllerAdvice(new GlobalApiExceptionHandler())
                .build();
    }

    /** Workbook một sheet TuVung; mỗi phần tử rows là {Từ, Cách đọc, Romaji, Nghĩa}. */
    private static MockMultipartFile vocabularyFile(String[]... rows) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("TuVung");
            String[] header = {"Từ *", "Cách đọc *", "Romaji", "Nghĩa *"};
            Row headerRow = sheet.createRow(0);
            for (int c = 0; c < header.length; c++) headerRow.createCell(c).setCellValue(header[c]);
            for (int r = 0; r < rows.length; r++) {
                Row row = sheet.createRow(r + 1);
                for (int c = 0; c < rows[r].length; c++) {
                    if (rows[r][c] != null) row.createCell(c).setCellValue(rows[r][c]);
                }
            }
            workbook.write(out);
            return new MockMultipartFile("file", "noi-dung.xlsx", XLSX, out.toByteArray());
        }
    }

    @Test
    void validFile_importsEveryRow() throws Exception {
        MockMultipartFile file = vocabularyFile(
                new String[]{"学生", "がくせい", "gakusei", "học sinh"},
                new String[]{"先生", "せんせい", "sensei", "giáo viên"});

        mvc.perform(multipart(URL).file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.imported").value(true))
                .andExpect(jsonPath("$.data.vocabularyCreated").value(2))
                .andExpect(jsonPath("$.data.errors").isEmpty());

        ArgumentCaptor<VocabularyCreateRequest> captor = ArgumentCaptor.forClass(VocabularyCreateRequest.class);
        verify(contentAdminService, times(2)).createVocabulary(eq(LESSON_ID), captor.capture());
        assertThat(captor.getAllValues()).extracting(VocabularyCreateRequest::getExpression)
                .containsExactly("学生", "先生");
    }

    @Test
    void missingMeaning_returnsRowErrorAndWritesNothing() throws Exception {
        // Dòng 2 đúng, dòng 3 bỏ trống cột "Nghĩa *": cả file bị từ chối, kể cả dòng đúng.
        MockMultipartFile file = vocabularyFile(
                new String[]{"学生", "がくせい", "gakusei", "học sinh"},
                new String[]{"先生", "せんせい", "sensei", null});

        mvc.perform(multipart(URL).file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.imported").value(false))
                .andExpect(jsonPath("$.data.errors.length()").value(1))
                .andExpect(jsonPath("$.data.errors[0].sheet").value("TuVung"))
                .andExpect(jsonPath("$.data.errors[0].row").value(3))
                .andExpect(jsonPath("$.data.errors[0].message").value("Thiếu cột \"Nghĩa *\""))
                .andExpect(jsonPath("$.data.vocabularyCreated").value(0));

        verify(contentAdminService, never()).createVocabulary(anyLong(), any());
        verify(contentAdminService, never()).createGrammarPoint(anyLong(), any());
        verify(contentAdminService, never()).attachLessonKanji(anyLong(), any());
    }

    /** Đúng lỗi đã gặp trên trình duyệt: gửi JSON thay vì multipart thì bị từ chối, không chạm tới service. */
    @Test
    void jsonContentType_isRejectedWith415() throws Exception {
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnsupportedMediaType());

        verifyNoInteractions(contentAdminService);
    }
}
