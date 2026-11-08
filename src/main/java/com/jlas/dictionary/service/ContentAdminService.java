package com.jlas.dictionary.service;

import com.jlas.dictionary.dto.AttachVocabularyRequest;
import com.jlas.dictionary.dto.DialogueLineResponse;
import com.jlas.dictionary.dto.DialogueLineUpsertRequest;
import com.jlas.dictionary.dto.DialogueRoleResponse;
import com.jlas.dictionary.dto.DialogueRoleUpsertRequest;
import com.jlas.dictionary.dto.DialogueSceneResponse;
import com.jlas.dictionary.dto.DialogueSceneUpsertRequest;
import com.jlas.dictionary.dto.ExampleSentenceResponse;
import com.jlas.dictionary.dto.ExampleSentenceUpsertRequest;
import com.jlas.dictionary.dto.GrammarPointResponse;
import com.jlas.dictionary.dto.GrammarPointUpsertRequest;
import com.jlas.dictionary.dto.KanjiResponse;
import com.jlas.dictionary.dto.LessonKanjiRequest;
import com.jlas.dictionary.dto.LessonSummaryResponse;
import com.jlas.dictionary.dto.LessonUpsertRequest;
import com.jlas.dictionary.dto.ReorderRequest;
import com.jlas.dictionary.dto.SectionResponse;
import com.jlas.dictionary.dto.SectionUpsertRequest;
import com.jlas.dictionary.dto.PagedResponse;
import com.jlas.dictionary.dto.TagResponse;
import com.jlas.dictionary.dto.VocabularyCreateRequest;
import com.jlas.dictionary.dto.VocabularySearchItem;
import com.jlas.dictionary.dto.TagUpsertRequest;
import com.jlas.dictionary.dto.TextbookResponse;
import com.jlas.dictionary.dto.TextbookUnitsResponse;
import com.jlas.dictionary.dto.TextbookUpsertRequest;
import com.jlas.dictionary.dto.UnitResponse;
import com.jlas.dictionary.dto.UnitUpsertRequest;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Service quản trị cây nội dung số hoá (giáo trình → unit → bài) cho vai CONTENT_CREATOR.
 * Tách khỏi {@link DictionaryService} vì hai bên nhìn dữ liệu bằng hai con mắt khác nhau:
 * <ul>
 *   <li>{@code DictionaryService} phục vụ màn hình duyệt/học và LUÔN giấu bản ghi ARCHIVED,
 *       với learner lẫn CONTENT_CREATOR.</li>
 *   <li>Service này phục vụ màn hình quản lý và LUÔN trả mọi trạng thái, không cần cờ nào cả.
 *       Quyền do {@code @PreAuthorize("hasRole('CONTENT_CREATOR')")} ở controller quyết định —
 *       cái gì thấy được phụ thuộc vai, không phụ thuộc tham số người gọi truyền vào.</li>
 * </ul>
 *
 * <h2>Xoá mềm</h2>
 * Giáo trình và bài học có cột status với CHECK cho phép ARCHIVED nên dùng luôn giá trị đó làm xoá mềm
 * (đi qua PATCH status sẵn có). Unit KHÔNG có cột status nên chỉ cho xoá cứng khi rỗng — xem
 * {@link #deleteUnit(Long)}.
 *
 * <h2>Không có xoá cứng giáo trình và bài học</h2>
 * Mọi FK trong cây này đều ON DELETE CASCADE, và lesson_progress cũng cascade theo lesson. Một lệnh
 * xoá giáo trình sẽ quét sạch unit, bài, nội dung lẫn tiến độ học viên. Vì vậy service này cố ý
 * không có phương thức xoá cứng nào cho hai cấp đó.
 */
public interface ContentAdminService {

    /** Toàn bộ giáo trình cho màn hình quản lý, KỂ CẢ đã lưu trữ. */
    List<TextbookResponse> listAllTextbooks();

    /** Cây một giáo trình kèm mọi unit và mọi bài, KỂ CẢ đã lưu trữ. */
    TextbookUnitsResponse getTextbookTree(Long textbookId);

    TextbookResponse createTextbook(TextbookUpsertRequest request);

    /**
     * Sửa thông tin giáo trình. Giáo trình đã lưu trữ phải khôi phục trước mới sửa được — việc khôi phục
     * đi qua PATCH /textbooks/{id}/status nên quy tắc này không khoá mất lối ra.
     *
     * @throws com.jlas.common.exception.ApiException CONFLICT nếu giáo trình đã lưu trữ
     */
    TextbookResponse updateTextbook(Long textbookId, TextbookUpsertRequest request);

    /**
     * Tải lên/thay ảnh thumbnail giáo trình (JPG/PNG/WEBP, tối đa 2MB, kiểm magic bytes). Ảnh cũ bị xoá
     * khỏi ổ đĩa sau khi lưu DB thành công.
     *
     * @throws com.jlas.common.exception.ApiException INVALID_IMAGE / PAYLOAD_TOO_LARGE / BAD_REQUEST nếu file
     *                                               không hợp lệ; CONFLICT nếu giáo trình đã lưu trữ
     */
    TextbookResponse uploadTextbookThumbnail(Long textbookId, MultipartFile file);

    /** Gỡ ảnh thumbnail (về thumbnail tự sinh) và xoá file trên ổ đĩa; chưa có ảnh thì không làm gì. */
    TextbookResponse removeTextbookThumbnail(Long textbookId);

    /**
     * Thêm unit vào cuối một giáo trình.
     *
     * @throws com.jlas.common.exception.ApiException NOT_FOUND nếu giáo trình không tồn tại;
     *                                               CONFLICT nếu giáo trình đã lưu trữ
     */
    UnitResponse createUnit(Long textbookId, UnitUpsertRequest request);

    UnitResponse updateUnit(Long unitId, UnitUpsertRequest request);

    /**
     * Xoá cứng unit. Chỉ cho phép khi unit không còn bài nào (kể cả bài đã lưu trữ).
     *
     * @throws com.jlas.common.exception.ApiException CONFLICT nếu unit còn bài
     */
    void deleteUnit(Long unitId);

    /**
     * Thêm bài vào cuối một unit.
     *
     * @throws com.jlas.common.exception.ApiException NOT_FOUND nếu unit hoặc tag không tồn tại;
     *                                               CONFLICT nếu giáo trình chứa unit đã lưu trữ
     */
    LessonSummaryResponse createLesson(Long unitId, LessonUpsertRequest request);

    LessonSummaryResponse updateLesson(Long lessonId, LessonUpsertRequest request);

    /**
     * Xoá cứng bài học. Chỉ dành cho bài tạo nhầm: phải còn ở trạng thái nháp, chưa có nội dung biên soạn,
     * chưa học viên nào chạm tới và không có quiz nào gắn vào.
     *
     * @throws com.jlas.common.exception.ApiException CONFLICT kèm lý do cụ thể nếu vi phạm bất kỳ điều kiện nào
     */
    void deleteLesson(Long lessonId);

    /**
     * Đánh số lại thứ tự unit trong một giáo trình theo đúng danh sách id gửi lên (order_index từ 1).
     * Danh sách phải gồm ĐÚNG các id hiện có, không thiếu không thừa không trùng.
     *
     * @throws com.jlas.common.exception.ApiException BAD_REQUEST nếu danh sách id không khớp;
     *                                               CONFLICT nếu giáo trình đã lưu trữ
     */
    List<UnitResponse> reorderUnits(Long textbookId, ReorderRequest request);

    /**
     * Đánh số lại thứ tự bài trong một unit theo đúng danh sách id gửi lên (order_index từ 1).
     *
     * @throws com.jlas.common.exception.ApiException BAD_REQUEST nếu danh sách id không khớp;
     *                                               CONFLICT nếu giáo trình chứa unit đã lưu trữ
     */
    List<LessonSummaryResponse> reorderLessons(Long unitId, ReorderRequest request);

    /* ── Phần nội dung trong bài (lesson_section) ────────────────────────────
     * Cấp chứa của mọi nội dung bên trong bài: câu ví dụ, lời hội thoại và liên kết từ vựng đều là
     * content_item, mà content_item gắn vào section chứ không gắn thẳng vào lesson.
     */

    /** Các phần nội dung của một bài, theo thứ tự hiện tại. */
    List<SectionResponse> listSections(Long lessonId);

    /**
     * Thêm phần nội dung vào cuối một bài.
     *
     * @throws com.jlas.common.exception.ApiException NOT_FOUND nếu bài không tồn tại;
     *                                               CONFLICT nếu giáo trình chứa bài đã lưu trữ
     */
    SectionResponse createSection(Long lessonId, SectionUpsertRequest request);

    SectionResponse updateSection(Long sectionId, SectionUpsertRequest request);

    /**
     * Xoá cứng phần nội dung. Chỉ cho phép khi phần không còn mục nội dung nào — fk_content_item_section
     * là ON DELETE CASCADE nên xoá phần còn nội dung sẽ kéo theo câu ví dụ, lời hội thoại và liên kết
     * từ vựng của bài. Cùng quy tắc với unit: cấp không có cột status thì chỉ xoá cứng khi đã rỗng.
     *
     * @throws com.jlas.common.exception.ApiException CONFLICT nếu phần còn mục nội dung
     */
    void deleteSection(Long sectionId);

    /** Đánh số lại thứ tự phần nội dung trong một bài (order_index từ 1). */
    List<SectionResponse> reorderSections(Long lessonId, ReorderRequest request);

    /* ── Ngữ pháp (grammar_point + câu ví dụ) ────────────────────────────────
     * grammar_point gắn THẲNG vào lesson, không qua section. Còn câu ví dụ là content_item loại GRAMMAR
     * nên vẫn phải nằm trong một section — xem createExample.
     */

    /** Mẫu ngữ pháp của một bài, theo thứ tự hiện tại, kèm số câu ví dụ đang gắn. */
    List<GrammarPointResponse> listGrammarPoints(Long lessonId);

    /**
     * Thêm mẫu ngữ pháp vào cuối một bài.
     *
     * @throws com.jlas.common.exception.ApiException NOT_FOUND nếu bài hoặc tag không tồn tại;
     *                                               CONFLICT nếu giáo trình chứa bài đã lưu trữ
     */
    GrammarPointResponse createGrammarPoint(Long lessonId, GrammarPointUpsertRequest request);

    GrammarPointResponse updateGrammarPoint(Long grammarPointId, GrammarPointUpsertRequest request);

    /**
     * Xoá cứng mẫu ngữ pháp. CHẶN khi còn câu ví dụ trỏ tới, giống quy tắc của unit và phần nội dung.
     * <p>
     * Lý do không để xoá thẳng: fk_content_item_grammar là ON DELETE SET NULL, nên xoá mẫu sẽ khiến các
     * câu ví dụ của nó mất liên kết trong im lặng — chúng vẫn nằm trong section nhưng rơi vào nhóm
     * "Câu ví dụ khác" ở giao diện học, không ai biết chúng từng thuộc mẫu nào. Mất dữ liệu ngữ nghĩa
     * mà không có cảnh báo là thứ ticket này muốn tránh.
     *
     * @throws com.jlas.common.exception.ApiException CONFLICT nếu mẫu còn câu ví dụ
     */
    void deleteGrammarPoint(Long grammarPointId);

    /** Đánh số lại thứ tự mẫu ngữ pháp trong một bài (order_index từ 1). */
    List<GrammarPointResponse> reorderGrammarPoints(Long lessonId, ReorderRequest request);

    /** Câu ví dụ đang gắn vào một mẫu ngữ pháp. */
    List<ExampleSentenceResponse> listExamples(Long grammarPointId);

    /**
     * Thêm câu ví dụ cho một mẫu ngữ pháp.
     * <p>
     * Câu ví dụ là content_item nên bắt buộc phải nằm trong một section. Request có thể chỉ định
     * {@code sectionId}; để trống thì service dùng phần loại EXAMPLE đầu tiên của bài, và tạo một phần
     * tên "Câu ví dụ" nếu bài chưa có phần EXAMPLE nào. Chọn cách này thay vì bắt người dùng tạo
     * section trước, vì "phần nội dung" là chi tiết lưu trữ của content_item chứ không phải khái niệm
     * người biên soạn nghĩ tới khi thêm một câu ví dụ; phần được tạo vẫn hiện bình thường trong danh
     * sách section nên không có gì bị giấu.
     *
     * @throws com.jlas.common.exception.ApiException NOT_FOUND nếu mẫu hoặc section không tồn tại;
     *                                               BAD_REQUEST nếu section thuộc bài khác;
     *                                               CONFLICT nếu giáo trình đã lưu trữ
     */
    ExampleSentenceResponse createExample(Long grammarPointId, ExampleSentenceUpsertRequest request);

    ExampleSentenceResponse updateExample(Long contentItemId, ExampleSentenceUpsertRequest request);

    /** Xoá cứng một câu ví dụ. Không có chốt chặn nào: câu ví dụ là mục lá, không có gì phụ thuộc nó. */
    void deleteExample(Long contentItemId);

    /* ── Từ vựng của bài (content_vocabulary) ────────────────────────────────
     * Tìm và gắn từ đã có, hoặc tạo từ mới (createVocabulary). vocabulary_entry là bảng dùng chung toàn hệ
     * thống, có uk_vocabulary_normalized trên cặp cột normalized_*, nên từ mới được chuẩn hoá bằng
     * DictionaryUtil (cùng quy tắc với script import dữ liệu) và kiểm trùng trước khi tạo.
     */

    /**
     * Tìm từ để gắn vào bài, có phân trang. Khớp trên cột expression/reading thường (collation bỏ qua
     * dakuten nên gõ thiếu dấu đục vẫn ra) — đây là đường đọc cho người dùng, không phải đường kiểm trùng.
     *
     * @param lessonId bài đang soạn, để đánh dấu từ nào đã gắn rồi; có thể null
     */
    PagedResponse<VocabularySearchItem> searchVocabulary(String keyword, Long lessonId, int page, int size);

    /** Từ vựng hiện có trong một bài. */
    List<VocabularySearchItem> listLessonVocabulary(Long lessonId);

    /**
     * Gắn một từ có sẵn vào bài.
     * <p>
     * Từ vựng nối vào bài qua content_vocabulary → content_item → lesson_section, nên cần một mục nội
     * dung làm chỗ chứa. Theo đúng quy ước của dữ liệu hiện có (seed): phần loại EXPLANATION tên
     * "Từ vựng", bên trong là content_item loại READING tên "Danh sách từ vựng". Service tái dùng chỗ
     * chứa đó nếu bài đã có, chưa có thì tạo.
     *
     * @throws com.jlas.common.exception.ApiException NOT_FOUND nếu bài hoặc từ không tồn tại;
     *                                               BAD_REQUEST nếu section thuộc bài khác;
     *                                               CONFLICT nếu từ đã có trong bài, hoặc giáo trình đã lưu trữ
     */
    VocabularySearchItem attachVocabulary(Long lessonId, AttachVocabularyRequest request);

    /**
     * Tạo từ mới rồi gắn vào bài. Nếu từ điển đã có từ cùng cặp (cách viết, cách đọc) sau chuẩn hoá thì
     * KHÔNG tạo bản trùng mà gắn luôn từ có sẵn, và không sửa gì trên từ đó (từ dùng chung cho mọi bài).
     * <p>
     * Từ mới mang usage_scope = TEXTBOOK, dictionary_status = DRAFT; vocabulary_kanji được điền theo các chữ
     * Kanji trong cách viết (chữ chưa có trong bảng kanji thì bỏ qua) để tab Kanji của bài nhận ra nó.
     *
     * @throws com.jlas.common.exception.ApiException NOT_FOUND nếu bài không tồn tại;
     *                                               CONFLICT nếu từ đã có trong bài, hoặc giáo trình đã lưu trữ
     */
    VocabularySearchItem createVocabulary(Long lessonId, VocabularyCreateRequest request);

    /**
     * Gỡ một từ khỏi bài.
     * <p>
     * CHỈ xoá các dòng content_vocabulary nối từ đó với bài này. TUYỆT ĐỐI không đụng tới
     * vocabulary_entry: một từ dùng chung cho mọi bài và cho cả tra cứu từ điển, xoá nó sẽ làm từ biến
     * mất ở mọi nơi khác — và vì fk_content_vocab_vocabulary là ON DELETE CASCADE, các bài khác sẽ mất
     * từ trong im lặng.
     *
     * @throws com.jlas.common.exception.ApiException NOT_FOUND nếu từ không nằm trong bài;
     *                                               CONFLICT nếu giáo trình đã lưu trữ
     */
    void detachVocabulary(Long lessonId, Long vocabularyId);

    /* ── Kanji gắn tay vào bài (lesson_kanji) ───────────────────────────────
     * Độc lập với kanji suy ra từ từ vựng: trang học viên hiện cả hai, kanji gắn tay đứng trước.
     * Chỉ gắn được chữ đã có trong bảng kanji (dữ liệu KANJIDIC2); không tạo Kanji mới ở đây.
     */

    /** Kanji gắn tay của một bài theo thứ tự hiển thị, kèm cách đọc On/Kun. */
    List<KanjiResponse> listLessonKanji(Long lessonId);

    /**
     * Gắn các chữ Kanji trong chuỗi vào cuối danh sách của bài. Ký tự không phải Kanji bị bỏ qua, chữ đã
     * gắn rồi được giữ nguyên chỗ cũ.
     *
     * @return toàn bộ kanji gắn tay của bài sau khi gắn
     * @throws com.jlas.common.exception.ApiException BAD_REQUEST nếu chuỗi không có chữ Kanji nào, hoặc có
     *                                               chữ chưa có trong bảng kanji (không gắn chữ nào cả);
     *                                               CONFLICT nếu giáo trình đã lưu trữ
     */
    List<KanjiResponse> attachLessonKanji(Long lessonId, LessonKanjiRequest request);

    /**
     * Gỡ một kanji gắn tay khỏi bài. Chỉ xoá dòng lesson_kanji, không đụng tới bảng kanji. Kanji suy ra
     * từ từ vựng không gỡ ở đây được — muốn bỏ thì gỡ từ chứa nó.
     *
     * @throws com.jlas.common.exception.ApiException NOT_FOUND nếu kanji không được gắn tay vào bài;
     *                                               CONFLICT nếu giáo trình đã lưu trữ
     */
    void detachLessonKanji(Long lessonId, Long kanjiId);

    /* ── Hội thoại (dialogue_role + content_item LISTENING + detail + chunk) ──
     * Theo quy ước của dữ liệu hiện có: mỗi ĐOẠN hội thoại là một lesson_section loại EXAMPLE với title
     * "Hội thoại: <tên cảnh>"; mỗi LỜI là một content_item loại LISTENING trong đoạn đó, kèm một dòng
     * dialogue_line_detail (vai, vị trí bong bóng) và các dialogue_line_chunk (cụm tách nghĩa).
     */

    /** Vai hội thoại của một bài. */
    List<DialogueRoleResponse> listDialogueRoles(Long lessonId);

    /**
     * Thêm vai vào bài.
     *
     * @throws com.jlas.common.exception.ApiException CONFLICT nếu mã vai đã có trong bài
     *                                               (uk_dialogue_role_lesson_code), hoặc giáo trình đã lưu trữ
     */
    DialogueRoleResponse createDialogueRole(Long lessonId, DialogueRoleUpsertRequest request);

    DialogueRoleResponse updateDialogueRole(Long roleId, DialogueRoleUpsertRequest request);

    /**
     * Xoá vai. CHẶN khi vai còn lời thoại — cùng lý do với mẫu ngữ pháp: fk_dialogue_detail_role là
     * ON DELETE SET NULL, xoá thẳng sẽ khiến các lời mất tên người nói mà không báo lỗi gì, ở giao diện
     * học bong bóng hiện tên trống.
     *
     * @throws com.jlas.common.exception.ApiException CONFLICT nếu vai còn lời thoại
     */
    void deleteDialogueRole(Long roleId);

    /** Các đoạn hội thoại của bài, kèm toàn bộ lời và cụm. */
    List<DialogueSceneResponse> listDialogueScenes(Long lessonId);

    /** Tạo một đoạn hội thoại mới (service tự thêm tiền tố "Hội thoại: " vào tên). */
    DialogueSceneResponse createDialogueScene(Long lessonId, DialogueSceneUpsertRequest request);

    /**
     * Thêm lời vào cuối một đoạn hội thoại.
     * <p>
     * Nếu request có danh sách cụm thì ghép chúng lại PHẢI ra đúng {@code textJa}. Đây là bất biến quan
     * trọng nhất của phần hội thoại: giao diện học dựng lại câu hoàn toàn từ các cụm
     * ({@code chunks.map(...).join('')}) và bỏ qua content_item.content, nên lệch một ký tự là học viên
     * đọc một đằng, audio đọc một nẻo — mà không có lỗi nào hiện ra.
     *
     * @throws com.jlas.common.exception.ApiException BAD_REQUEST nếu ghép cụm không khớp câu, hoặc vai
     *                                               thuộc bài khác; CONFLICT nếu giáo trình đã lưu trữ
     */
    DialogueLineResponse createDialogueLine(Long sectionId, DialogueLineUpsertRequest request);

    /** Sửa lời thoại; danh sách cụm bị ghi đè TOÀN BỘ vì bất biến ghép-ra-đúng-câu chỉ kiểm được trên cả bộ. */
    DialogueLineResponse updateDialogueLine(Long contentItemId, DialogueLineUpsertRequest request);

    void deleteDialogueLine(Long contentItemId);

    /** Đánh số lại thứ tự lời trong một đoạn hội thoại (order_index từ 1). */
    List<DialogueLineResponse> reorderDialogueLines(Long sectionId, ReorderRequest request);

    /** Tag theo loại để đổ dropdown ở form bài học. */
    List<TagResponse> listTagsByType(String tagType);

    /** Tạo tag mới ngay tại form bài học (điểm kiến thức riêng cho từng bài). */
    TagResponse createTag(TagUpsertRequest request);
}
