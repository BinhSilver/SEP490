package com.jlas.dictionary.kanjimap;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jlas.auth.dto.ApiResponse;
import com.jlas.auth.security.CurrentUser;
import com.jlas.auth.security.UserPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import java.util.LinkedHashMap;
import java.util.Map;

@Controller
public class KanjiMapAdminController {
    private final KanjiMapContentService content;
    private final KanjiMapQuota quota;
    public KanjiMapAdminController(KanjiMapContentService content, KanjiMapQuota quota) {
        this.content = content;
        this.quota = quota;
    }

    @GetMapping("/admin/kanji-map")
    @PreAuthorize("hasRole('CONTENT_CREATOR')")
    public String page() { return "admin/kanji-map"; }

    @GetMapping("/api/dictionary/kanji-map/catalog")
    @ResponseBody
    public ApiResponse<Map<String, Object>> published(@AuthenticationPrincipal UserPrincipal user, HttpServletRequest request) {
        quota.requireAvailable(user == null ? null : user.userId(), request.getRemoteAddr());
        return ApiResponse.ok("OK", json(content.graph(false)));
    }

    @GetMapping("/api/admin/kanji-map/catalog")
    @PreAuthorize("hasRole('CONTENT_CREATOR')")
    @ResponseBody
    public ApiResponse<Map<String, Object>> catalog() { return ApiResponse.ok("OK", json(content.graph(true))); }

    @GetMapping("/api/admin/kanji-map/entries/{character}")
    @PreAuthorize("hasRole('CONTENT_CREATOR')")
    @ResponseBody
    public ApiResponse<Map<String, Object>> entry(@PathVariable String character) {
        return ApiResponse.ok("OK", view(content.entry(character)));
    }

    @PutMapping("/api/admin/kanji-map/entries/{character}")
    @PreAuthorize("hasRole('CONTENT_CREATOR')")
    @ResponseBody
    public ApiResponse<Map<String, Object>> save(@PathVariable String character, @RequestBody KanjiMapContentService.Edit edit) {
        return ApiResponse.ok("Đã lưu nội dung lược đồ.", view(content.save(character, edit, CurrentUser.requireUserId())));
    }

    public record Version(long version) {}
    @PutMapping("/api/admin/kanji-map/entries/{character}/reset")
    @PreAuthorize("hasRole('CONTENT_CREATOR')")
    @ResponseBody
    public ApiResponse<Map<String, Object>> reset(@PathVariable String character, @RequestBody Version version) {
        return ApiResponse.ok("Đã khôi phục nội dung nguồn.", view(content.reset(character, version.version(), CurrentUser.requireUserId())));
    }

    // Boot 4 uses Jackson 3 for HTTP. Transfer plain values rather than Jackson 2 tree objects.
    @SuppressWarnings("unchecked")
    private Map<String, Object> json(ObjectNode node) { return new ObjectMapper().convertValue(node, Map.class); }
    private Map<String, Object> view(KanjiMapContentService.Entry entry) {
        var result = new LinkedHashMap<String, Object>();
        result.put("node", json(entry.node())); result.put("source", json(entry.source()));
        result.put("version", entry.version()); result.put("updatedBy", entry.updatedBy());
        result.put("updatedAt", entry.updatedAt() == null ? null : entry.updatedAt().toString());
        return result;
    }
}
