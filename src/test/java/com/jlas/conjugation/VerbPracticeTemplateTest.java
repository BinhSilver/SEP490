package com.jlas.conjugation;
import java.util.Map;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.thymeleaf.context.*;
import org.thymeleaf.linkbuilder.ILinkBuilder;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import static org.junit.jupiter.api.Assertions.*;
class VerbPracticeTemplateTest {
    @Test void rendersPracticeAndResultsViews() throws Exception {
        var resolver=new ClassLoaderTemplateResolver();resolver.setPrefix("templates/");resolver.setSuffix(".html");resolver.setTemplateMode("HTML");resolver.setCharacterEncoding("UTF-8");
        var engine=new SpringTemplateEngine();engine.setTemplateResolver(resolver);
        engine.setLinkBuilder(new ILinkBuilder(){public String getName(){return "test";}public Integer getOrder(){return 1;}public String buildLink(IExpressionContext c,String b,Map<String,Object> p){return b;}});
        var html=engine.process("verb-practice",new Context());
        assertTrue(html.contains("/js/pages/verb-practice.js"));assertTrue(html.contains("id=\"vpResults\""));
        Files.createDirectories(Path.of("tmp"));Files.writeString(Path.of("tmp/verb-practice-rendered.html"),html);
    }
}
