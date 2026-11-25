package com.jlas.jlptpath.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * [JLAS-W0108] F220 (owner 22/11): the public About and user policy pages linked from the shared site footer
 * ({@code fragments/site-footer}). Static content only, the same for guests and members.
 */
@Controller
public class SiteInfoPageController {

    @GetMapping("/about")
    public String about() {
        return "site/about";
    }

    @GetMapping("/policy")
    public String policy() {
        return "site/policy";
    }
}
