package com.jlas.kaiwa.controller;

import com.jlas.kaiwa.model.Category;
import com.jlas.kaiwa.service.CatalogService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class HomeController {

    private final CatalogService catalogService;

    public HomeController(CatalogService catalogService) {
        this.catalogService = catalogService;
    }

    /** Entry: choose free-chat vs scenario mode. */
    @GetMapping({"/ai-call", "/ai-call/"})
    public String modeSelect() {
        return "aicall/mode-select";
    }

    /** Scenario library (AI Theo Kịch Bản) — previous /ai-call home. */
    @GetMapping({"/ai-call/scenarios", "/ai-call/home"})
    public String scenarios(Model model) {
        List<Category> categories = catalogService.listCategories();
        Map<Long, Long> counts = new LinkedHashMap<>();
        for (Category c : categories) {
            counts.put(c.getId(), catalogService.countScenarios(c.getId()));
        }
        model.addAttribute("categories", categories);
        model.addAttribute("scenarioCounts", counts);
        model.addAttribute("showWelcome", true);
        return "aicall/home";
    }
}
