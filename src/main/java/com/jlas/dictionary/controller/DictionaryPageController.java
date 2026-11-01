package com.jlas.dictionary.controller;

import com.jlas.dictionary.repository.LessonRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * Trang Thymeleaf duyệt giáo trình số hoá. Chỉ trả view name;
 * dữ liệu do trang tự gọi /api/dictionary/** với JWT của người dùng
 * (cùng cách với /profile, /goals) nên quyền xem DRAFT/PUBLISHED luôn theo đúng role.
 * /textbooks/** nằm trong anyRequest().authenticated() của SecurityConfig:
 * khách chưa đăng nhập bị chuyển về /login?redirect=... và quay lại sau khi đăng nhập.
 */
@Controller
@RequiredArgsConstructor
public class DictionaryPageController {

    private static final String SKILL_ONYOMI = "ONYOMI";

    private final LessonRepository lessonRepository;
    private final com.jlas.flashcard.repository.FlashcardTextbookLinks flashcardLinks;

    @GetMapping({"/search", "/search/{tab:word|kanji|example|grammar|japanese}",
            "/search/{tab:word|kanji|example|grammar|japanese}/{query}",
            "/search/grammar_details/{id}"})
    public String page() {
        return "forward:/dictionary/index.html";
    }

    @GetMapping("/textbooks")
    public String textbooks(Model model) {
        model.addAttribute("layoutMode", "app");
        return "textbooks/index";
    }

    /**
     * Màn hình quản lý cây nội dung cho CONTENT_CREATOR. Trang chỉ là khung: dữ liệu do JS gọi
     * /api/dictionary/manage/** với JWT, và các endpoint đó đã có @PreAuthorize nên vai khác mở
     * trang này cũng chỉ nhận 403 từ API, không thấy gì.
     */
    @GetMapping("/textbooks/manage")
    public String manageTextbooks(Model model) {
        model.addAttribute("layoutMode", "app");
        return "textbooks/manage";
    }

    /**
     * [JLAS2-17] Trang tạo giáo trình theo 3 bước (thay modal "Thêm giáo trình"). Cũng chỉ là khung: JS gọi đúng các
     * API modal cũ (POST /api/dictionary/textbooks, upload thumbnail) — đều @PreAuthorize CONTENT_CREATOR.
     * Đặt dưới /textbooks/manage (không phải /admin/**: SecurityConfig chỉ mở /admin/** cho MODERATOR/ADMIN).
     */
    @GetMapping("/textbooks/manage/new")
    public String newTextbook(Model model) {
        model.addAttribute("layoutMode", "app");
        return "textbooks/textbook-new";
    }

    /**
     * [JLAS2-17] Trang thêm bài học theo 3 bước (thay modal "Thêm bài học"). Bài luôn thuộc một phần:
     * ?unitId= chọn sẵn phần (từ nút "+" của phần ở danh sách); không có thì trang cho chọn phần của giáo trình.
     */
    @GetMapping("/textbooks/manage/{textbookId}/lessons/new")
    public String newLesson(@PathVariable Long textbookId,
                            @org.springframework.web.bind.annotation.RequestParam(required = false) Long unitId,
                            Model model) {
        model.addAttribute("layoutMode", "app");
        model.addAttribute("textbookId", textbookId);
        model.addAttribute("unitId", unitId);
        return "textbooks/lesson-new";
    }

    /**
     * Trang biên soạn nội dung bên trong một bài (ngữ pháp, từ vựng; hội thoại sẽ thêm sau).
     * <p>
     * Tách khỏi /textbooks/manage vì đây là màn hình soạn thảo — trường văn bản dài, cấu trúc lồng —
     * còn màn quản lý là điều hướng và trạng thái. Không đụng mapping /textbooks/{textbookId}/lessons/{id}:
     * đường dẫn này có "lessons" ở segment thứ hai nên không khớp mẫu đó.
     */
    @GetMapping("/textbooks/lessons/{lessonId}/edit")
    public String editLessonContent(@PathVariable Long lessonId, Model model) {
        model.addAttribute("layoutMode", "app");
        model.addAttribute("lessonId", lessonId);
        return "textbooks/lesson-editor";
    }

    @GetMapping("/textbooks/{textbookId}")
    public String textbookUnits(@PathVariable Long textbookId, Model model) {
        model.addAttribute("layoutMode", "app");
        model.addAttribute("textbookId", textbookId);
        return "textbooks/units";
    }

    /**
     * Bài Onyomi (giáo trình Âm On-Kun) không có content_item: nội dung là quy tắc chuyển âm (conversion_rule
     * + ví dụ) do trang /learning/onyomi/lessons/{id} hiển thị — chuyển hướng như /learning/lesson/{id} đang làm,
     * thay vì để trang giáo trình báo "Bài này chưa có Kanji".
     */
    @GetMapping("/textbooks/{textbookId}/lessons/{lessonId}")
    public String lesson(@PathVariable Long textbookId, @PathVariable Long lessonId, Model model) {
        boolean onyomi = lessonRepository.findById(lessonId)
                .map(l -> SKILL_ONYOMI.equals(l.getSkill()))
                .orElse(false);
        if (onyomi) return "redirect:/learning/onyomi/lessons/" + lessonId;
        model.addAttribute("layoutMode", "app");
        model.addAttribute("textbookId", textbookId);
        model.addAttribute("lessonId", lessonId);
        // Bộ flashcard sinh từ chính bài này (phần Từ vựng / Hán tự), nếu có.
        var links = flashcardLinks.forLesson(lessonId);
        model.addAttribute("flashcardLinks", links);
        // Nút "Ôn tập bằng Flashcard" ở tab Từ vựng mở thẳng bộ từ vựng của bài.
        model.addAttribute("flashcardVocabSetId", links.stream().filter(link -> !link.kanji())
                .map(com.jlas.flashcard.repository.FlashcardTextbookLinks.Link::setId).findFirst().orElse(null));
        return "textbooks/lesson";
    }
}
