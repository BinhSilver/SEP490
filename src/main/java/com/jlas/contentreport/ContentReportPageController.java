package com.jlas.contentreport;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
@Controller
public class ContentReportPageController {
    @GetMapping("/admin/learning-content-reports")
    public String reports(){return "admin/learning-content-reports";}
}
