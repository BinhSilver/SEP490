package com.jlas.kaiwa.controller;

import com.jlas.kaiwa.model.Category;
import com.jlas.kaiwa.service.CatalogService;
import java.util.Map;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@Controller
public class ScenarioPageController {

    private final CatalogService catalogService;

    public ScenarioPageController(CatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @GetMapping("/category/{id}")
    public String category(@PathVariable Long id, Model model) {
        Category category = catalogService.getCategory(id);
        model.addAttribute("category", category);
        model.addAttribute("scenarios", catalogService.listScenarios(id));
        return "aicall/category";
    }

    @GetMapping("/scenario/{id}")
    public String scenario(@PathVariable Long id, Model model) {
        Map<String, Object> detail = catalogService.scenarioDetail(id);
        model.addAllAttributes(detail);
        return "aicall/scenario";
    }
}
