package com.jlas.jlptpath.controller;

import com.jlas.auth.security.UserPrincipal;
import com.jlas.jlptpath.service.JpathEntryRoute;
import com.jlas.jlptpath.service.JpathEntryRouter;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.util.UriUtils;

/** [JLAS-W0108] {@code /learning}: JLPT learners get the path page or the entry flow, everyone else the old path. */
@Controller
@RequiredArgsConstructor
public class JpathEntryPageController {

    private static final Set<String> TABS = Set.of("vocab", "kanji", "grammar", "kana", "skills");
    private static final Pattern TOPIC = Pattern.compile("^[a-z0-9-]{1,60}$");

    private final JpathEntryRouter router;
    private final org.springframework.beans.factory.ObjectProvider<com.jlas.mypaths.MyPathsService> myPaths;

    String learning(UserPrincipal user, String tab, String topic, String q, String view, Model model) {
        return learning(user, tab, topic, q, view, null, model);
    }

    @GetMapping("/learning")
    public String learning(@AuthenticationPrincipal UserPrincipal user,
            @RequestParam(required = false) String tab,
            @RequestParam(required = false) String topic,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String view,
            @RequestParam(required = false) String tour,
            Model model) {
        if (user == null) {
            return "redirect:/login"; // I21: no learning page for a guest
        }
        // Owner 24/11 "Lộ trình của tôi": with two or more paths the plain /learning opens the dashboard; the JLPT
        // card links to ?view=jlpt, and deep links (tab / topic / word / tour) keep going to the path page as before
        boolean forceJlpt = "jlpt".equalsIgnoreCase(view);
        boolean deepLink = tab != null || topic != null || q != null || "1".equals(tour);
        if (!forceJlpt && !deepLink && myPathCount(user.userId()) >= 2) {
            return "redirect:/learning/my";
        }
        JpathEntryRoute route = router.route(user.userId());
        if ((forceJlpt || deepLink) && route == JpathEntryRoute.COMM && myPaths.getIfAvailable() != null && myPaths.getObject().jlptActive(user.userId())) {
            route = JpathEntryRoute.JPATH_PAGE;
        }
        switch (route) {
            case JPATH_START:
                return "redirect:/learning/jlpt/start";
            case COMM:
                // F166: "Học từ này" on the home word card links here with the word; a communication learner has no
                // JLPT list page, so the word opens in the dictionary instead of the communication path
                String word = q == null ? "" : q.strip();
                if ("vocab".equals(tabOrNull(tab)) && !word.isEmpty() && word.length() <= 60) {
                    return "redirect:/search/word/" + UriUtils.encodePathSegment(word, StandardCharsets.UTF_8);
                }
                // L19: kept (a communication learner has no JLPT path page), but the page now says why it opened
                return "redirect:/learning/comm?from=jlpt";
            case JPATH_PAGE:
                model.addAttribute("layoutMode", "app");
                model.addAttribute("jpathTab", tabOrNull(tab));
                model.addAttribute("jpathTopic", topic != null && TOPIC.matcher(topic).matches() ? topic : null);
                return "jlptpath/path";
            default:
                // [JLAS-W0108] Owner 07/11: no old path any more; no survey (or no path goal) starts at the survey
                return "redirect:/survey";
        }
    }

    private static String tabOrNull(String tab) {
        if (tab == null) {
            return null;
        }
        String lower = tab.toLowerCase(Locale.ROOT);
        return TABS.contains(lower) ? lower : null;
    }

    private int myPathCount(Long userId) {
        com.jlas.mypaths.MyPathsService m = myPaths.getIfAvailable();
        return m == null ? 0 : m.pathCount(userId);
    }
}
