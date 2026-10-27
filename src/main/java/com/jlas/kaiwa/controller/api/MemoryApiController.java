package com.jlas.kaiwa.controller.api;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.auth.security.CurrentUser;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.kaiwa.memory.MemorySearch;
import com.jlas.kaiwa.memory.MemoryStore;
import com.jlas.kaiwa.memory.MemoryTypes.Memory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/conversation/memories")
public class MemoryApiController {
    private final MemoryStore store;
    private final MemorySearch search;
    public MemoryApiController(MemoryStore store, MemorySearch search) {
        this.store = store; this.search = search;
    }

    @GetMapping("/search")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> search(@RequestParam String q,
            @RequestParam(defaultValue = "8") int limit) {
        if (q.isBlank() || q.length() > 1000)
            throw new ApiException(ErrorCode.BAD_REQUEST, "Nội dung tìm kiếm cần từ 1 đến 1000 ký tự.");
        var results = search.search(userId(), q, limit).stream().map(h -> view(h.memory(), h.score())).toList();
        return ResponseEntity.ok(ApiResponse.ok("Ký ức liên quan", results));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> list(
            @RequestParam(defaultValue = "0") int offset, @RequestParam(defaultValue = "50") int limit) {
        var results = store.memories(userId()).stream().sorted(Comparator.comparing(Memory::updatedAt).reversed())
                .skip(Math.max(0, offset)).limit(Math.min(Math.max(1, limit), 100)).map(m -> view(m, 0)).toList();
        return ResponseEntity.ok(ApiResponse.ok("Các ý đã ghi nhớ", results));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> forget(@PathVariable long id) {
        store.forget(userId(), id);
        return ResponseEntity.ok(ApiResponse.ok("Đã xóa ký ức"));
    }

    private long userId() {
        return CurrentUser.requireUserId();
    }

    private Map<String, Object> view(Memory memory, double score) {
        return Map.of("id", memory.id(), "kind", memory.kind(), "topic", memory.topic(), "content", memory.content(),
                "sourceSessionId", memory.sourceSessionId(), "updatedAt", memory.updatedAt(),
                "expiresAt", memory.expiresAt() == null ? "" : memory.expiresAt(), "score", score);
    }
}
