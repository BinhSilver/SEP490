package com.jlas.shadowing.controller;

import com.jlas.shadowing.service.DictionaryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController("shadowingDictionaryController")
@RequestMapping("/api/dictionary")
public class DictionaryController {

    @Autowired private DictionaryService dictionaryService;

    @GetMapping
    public ResponseEntity<Map<String, Object>> lookup(@RequestParam String q) {
        try {
            return ResponseEntity.ok(dictionaryService.lookup(q));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }
}
