package com.jlas.learning;

import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.thymeleaf.context.Context;
import org.thymeleaf.context.IExpressionContext;
import org.thymeleaf.linkbuilder.ILinkBuilder;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

class WeaknessNotebookTemplateTest {
    @Test void rendersCompleteNotebookWithSharedHead() {
        var resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode("HTML");
        resolver.setCharacterEncoding("UTF-8");
        var engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        engine.setLinkBuilder(new ILinkBuilder() {
            public String getName() { return "test-links"; }
            public Integer getOrder() { return 1; }
            public String buildLink(IExpressionContext context, String base, Map<String,Object> parameters) { return base; }
        });
        String html = engine.process("learning/weakness-notebook", new Context());
        assertTrue(html.contains("Sổ tay của tôi - JLAS"));
        assertTrue(html.contains("id=\"notebookOverview\""));
        assertTrue(html.contains("id=\"notebookStudy\""));
        assertTrue(html.contains("/js/pages/weakness-notebook.js"));
        String study = engine.process("jlpt-study", new Context());
        assertTrue(study.contains("studyVocabularyLink"));
        assertTrue(study.contains("studyGrammarLink"));
        assertTrue(study.contains("/js/pages/jlpt-study.js"));
    }
}
