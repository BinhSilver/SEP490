package com.jlas.kaiwa.controller;

import com.jlas.kaiwa.model.Category;
import com.jlas.kaiwa.model.Scenario;
import com.jlas.kaiwa.util.ScenarioIcons;
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
        Map<Long, List<Scenario>> scenariosByCategory = new LinkedHashMap<>();
        Map<Long, String> icons = new LinkedHashMap<>();
        for (Category c : categories) {
            var roots = catalogService.listScenarios(c.getId());
            counts.put(c.getId(), (long) roots.size());
            scenariosByCategory.put(c.getId(), roots);
            roots.forEach(s -> icons.put(s.getId(), ScenarioIcons.topic(s)));
        }
        model.addAttribute("categories", categories);
        model.addAttribute("scenarioCounts", counts);
        model.addAttribute("scenariosByCategory", scenariosByCategory);
        model.addAttribute("scenarioIcons", icons);
        model.addAttribute("showWelcome", true);
        return "aicall/home";
    }
}
