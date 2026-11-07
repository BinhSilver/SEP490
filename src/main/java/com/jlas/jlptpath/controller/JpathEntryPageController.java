package com.jlas.jlptpath.controller;

import com.jlas.auth.security.UserPrincipal;
import com.jlas.jlptpath.service.JpathEntryRouter;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** [JLAS-W0108] {@code /learning}: JLPT learners get the path page or the entry flow, everyone else the old path. */
@Controller
@RequiredArgsConstructor
public class JpathEntryPageController {

    private static final Set<String> TABS = Set.of("vocab", "kanji", "grammar", "kana", "skills");
    private static final Pattern TOPIC = Pattern.compile("^[a-z0-9-]{1,60}$");

    private final JpathEntryRouter router;

    @GetMapping("/learning")
    public String learning(@AuthenticationPrincipal UserPrincipal user,
            @RequestParam(required = false) String tab,
            @RequestParam(required = false) String topic,
            Model model) {
        switch (router.route(user == null ? null : user.userId())) {
            case JPATH_START:
                return "redirect:/learning/jlpt/start";
            case COMM:
                return "redirect:/learning/comm";
            case JPATH_PAGE:
                model.addAttribute("layoutMode", "app");
                model.addAttribute("jpathTab", tabOrNull(tab));
                model.addAttribute("jpathTopic", topic != null && TOPIC.matcher(topic).matches() ? topic : null);
                return "jlptpath/path";
            default:
                return "redirect:/learning/path";
        }
    }

    private static String tabOrNull(String tab) {
        if (tab == null) {
            return null;
        }
        String lower = tab.toLowerCase(Locale.ROOT);
        return TABS.contains(lower) ? lower : null;
    }
}
