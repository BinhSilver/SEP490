package com.jlas.kaiwa.controller;

import com.jlas.kaiwa.service.CatalogService;
import com.jlas.kaiwa.service.scenario.StageProgressService;
import com.jlas.kaiwa.model.Scenario;
import com.jlas.auth.security.UserPrincipal;
import com.jlas.kaiwa.util.StageFlowSupport;
import java.util.LinkedHashMap;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import java.util.Map;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class ScenarioPageController {

    private final CatalogService catalogService;
    private final StageProgressService stages;

    public ScenarioPageController(CatalogService catalogService,StageProgressService stages) {
        this.catalogService = catalogService;
        this.stages=stages;
    }

    @GetMapping("/category/{id}")
    public String category(@PathVariable Long id, Model model,@AuthenticationPrincipal UserPrincipal principal) {
        model.addAllAttributes(catalogService.categoryListing(id));
        return "aicall/category";
    }

    @GetMapping("/scenario/{id}")
    public String scenario(@PathVariable Long id, Model model,@AuthenticationPrincipal UserPrincipal principal,
                           @RequestParam(required=false) String locked,
                           @RequestParam(required=false) String stage) {
        Map<String, Object> detail = catalogService.scenarioDetail(id, stage);
        model.addAllAttributes(detail);
        var scenario=(Scenario)detail.get("scenario");
        var root=scenario.getParentScenario()==null?scenario:scenario.getParentScenario();
        if(!stages.definitions(root).isEmpty()) {
            model.addAttribute("progression",stages.view(root,principal.userId()));
            var selected=stages.definitions(root).stream().filter(s->s.code().equals(stage)).findFirst();
            selected.ifPresent(s->model.addAttribute("selectedStage",s.code()));
        }
        model.addAttribute("lockedNotice",locked!=null);
        return "aicall/scenario";
    }

    @GetMapping({"/ai-call/history","/scenario/{id}/history"})
    public String history(@PathVariable(required=false) Long id,Model model,@AuthenticationPrincipal UserPrincipal principal) {
        var attempts=stages.history(principal.userId(),id);
        var titles=new LinkedHashMap<Long,String>();
        attempts.forEach(s->titles.put(s.getId(),stages.stageTitle(s)));
        model.addAttribute("attempts",attempts);model.addAttribute("stageTitles",titles);model.addAttribute("scenarioId",id);
        return "aicall/history";
    }
}
