package com.jlas.shadowing.controller;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.shadowing.dto.LibraryPageDto;
import com.jlas.shadowing.dto.SeriesCreateRequest;
import com.jlas.shadowing.dto.SeriesDetailDto;
import com.jlas.shadowing.dto.SeriesSummaryDto;
import com.jlas.shadowing.service.SeriesService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/series")
public class SeriesController {
    private final SeriesService seriesService;

    public SeriesController(SeriesService seriesService) { this.seriesService = seriesService; }

    @GetMapping("/public")
    public List<SeriesSummaryDto> publicSeries(@RequestParam(required = false) String q,
                                               @RequestParam(required = false, defaultValue = "newest") String sort,
                                               @RequestParam(required = false) String category,
                                               @RequestParam(required = false) String level) {
        return seriesService.listPublic(q, sort, category, level);
    }

    @GetMapping("/public/page")
    public ResponseEntity<ApiResponse<LibraryPageDto<SeriesSummaryDto>>> publicPage(
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "popular") String sort,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String genre,
            @RequestParam(required = false) String level,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "12") int size) {
        return ResponseEntity.ok(ApiResponse.ok("Danh sách Shadowing",
                seriesService.listPublicPage(q, sort, category, genre, level, page, size)));
    }

    @GetMapping("/{id}")
    public SeriesDetailDto get(@PathVariable Long id) { return seriesService.get(id); }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody SeriesCreateRequest request) {
        try { return ResponseEntity.status(HttpStatus.CREATED).body(seriesService.create(request)); }
        catch (IllegalArgumentException e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }
}
