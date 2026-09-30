package com.jlas.shadowing.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class ShadowingPageController {

    @GetMapping({"/shadowing", "/shadowing/"})
    public String library() {
        return "shadowing/index";
    }

    @GetMapping("/shadowing/upload")
    public String upload() {
        return "shadowing/upload";
    }

    /** Compatibility for an early processing-page redirect. */
    @GetMapping("/shadowing/create")
    public String legacyCreate(@RequestParam(value = "cancelled", required = false) Boolean cancelled) {
        return Boolean.TRUE.equals(cancelled)
                ? "redirect:/shadowing/upload?cancelled=1"
                : "redirect:/shadowing/upload";
    }

    @GetMapping("/shadowing/create/{id}/processing")
    public String processing(@PathVariable Long id, Model model) {
        model.addAttribute("videoId", id);
        return "shadowing/processing";
    }

    @GetMapping("/shadowing/editor/{id}")
    public String editor(@PathVariable Long id, Model model) {
        model.addAttribute("videoId", id);
        return "shadowing/editor";
    }

    @GetMapping("/shadowing/prepare/{id}")
    public String prepare(@PathVariable Long id, Model model) {
        model.addAttribute("videoId", id);
        return "shadowing/preparation";
    }

    @GetMapping("/shadowing/practice/{id}")
    public String practice(@PathVariable Long id, Model model) {
        model.addAttribute("videoId", id);
        return "shadowing/practice-select";
    }

    @GetMapping("/shadowing/exercise/{sessionId}")
    public String exercise(@PathVariable Long sessionId, Model model) {
        model.addAttribute("sessionId", sessionId);
        return "shadowing/exercise";
    }

    @GetMapping("/shadowing/player/{id}")
    public String player(@PathVariable Long id,
                         @RequestParam(required = false) String mode,
                         Model model) {
        if (!"shadowing".equalsIgnoreCase(mode)) {
            return "redirect:/shadowing/practice/" + id;
        }
        model.addAttribute("videoId", id);
        return "shadowing/player";
    }

    @GetMapping("/shadowing/progress")
    public String progress() {
        return "shadowing/progress";
    }

    @GetMapping("/shadowing/series/{id}")
    public String series(@PathVariable Long id, Model model) {
        model.addAttribute("seriesId", id);
        return "shadowing/series";
    }

    /** Alias: một số user/bookmark dùng /conversation/{id} thay vì /player/{id}. */
    @GetMapping("/shadowing/conversation/{id}")
    public String conversationAlias(@PathVariable Long id) {
        return "redirect:/shadowing/practice/" + id;
    }

    /* Legacy paths from standalone shadowing app (JS used these without /shadowing prefix) */
    @GetMapping("/create/{id}/processing")
    public String legacyProcessing(@PathVariable Long id) {
        return "redirect:/shadowing/create/" + id + "/processing";
    }

    @GetMapping("/editor/{id}")
    public String legacyEditor(@PathVariable Long id) {
        return "redirect:/shadowing/editor/" + id;
    }

    @GetMapping("/player/{id}")
    public String legacyPlayer(@PathVariable Long id) {
        return "redirect:/shadowing/practice/" + id;
    }
}
