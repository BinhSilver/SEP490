package com.jlas.dictionary.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class DictionaryPageController {
    @GetMapping({"/search", "/search/{tab:word|kanji|example|grammar|japanese}",
            "/search/{tab:word|kanji|example|grammar|japanese}/{query}",
            "/search/grammar_details/{id}"})
    public String page() {
        return "forward:/dictionary/index.html";
    }
}
