package com.jlas.conjugation;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.jdbc.Sql;
import static org.junit.jupiter.api.Assertions.*;
import static com.jlas.conjugation.VerbEngine.*;

@DataJpaTest(properties={"spring.profiles.include=","spring.datasource.url=jdbc:h2:mem:verb-engine;MODE=MySQL;DB_CLOSE_DELAY=-1","spring.jpa.hibernate.ddl-auto=create-drop"},showSql=false)
@ContextConfiguration(classes=VerbPracticePersistenceTest.Cfg.class)
@Sql(scripts="file:sql/seeds/verb_practice_catalog.sql")
class VerbEngineTest {
    @Autowired VerbCatalog catalog;
    @ParameterizedTest @CsvSource({
        "kaku,TE,書いて,かいて", "oyogu,TE,泳いで,およいで", "hanasu,TE,話して,はなして",
        "kau,TE,買って,かって", "matsu,TA,待った,まった", "kaeru,TE,帰って,かえって",
        "nomu,TA,飲んだ,のんだ", "asobu,TE,遊んで,あそんで", "shinu,TA,死んだ,しんだ",
        "iku,TE,行って,いって", "iku,TARA,行ったら,いったら", "aru,NAI,ない,ない",
        "aru,NAKATTA,なかった,なかった", "kau,NAI,買わない,かわない", "hashiru,MASU,走ります,はしります",
        "taberu,TE,食べて,たべて", "taberu,NAKATTA,食べなかった,たべなかった",
        "kiru_cut,TE,切って,きって", "kiru_wear,TE,着て,きて",
        "kaku,POTENTIAL,書ける,かける", "taberu,POTENTIAL,食べられる,たべられる",
        "kuru,MASU,来ます,きます", "kuru,NAI,来ない,こない", "kuru,BA,来れば,くれば",
        "kuru,POTENTIAL,来られる,こられる", "kuru,VOLITIONAL,来よう,こよう",
        "suru,POTENTIAL,できる,できる", "benkyou,NAI,勉強しない,べんきょうしない",
        "benkyou,POTENTIAL,勉強できる,べんきょうできる", "suru,PASSIVE,される,される",
        "kaku,CAUSATIVE,書かせる,かかせる", "kaku,CAUSATIVE_PASSIVE,書かせられる,かかせられる",
        "hanasu,CAUSATIVE_PASSIVE,話させられる,はなさせられる", "taberu,CAUSATIVE,食べさせる,たべさせる",
        "taberu,CAUSATIVE_PASSIVE,食べさせられる,たべさせられる", "kaku,VOLITIONAL,書こう,かこう",
        "kaku,BA,書けば,かけば", "neru,VOLITIONAL,寝よう,ねよう", "suru,BA,すれば,すれば" })
    void conjugatesUsingIndependentExpectedForms(String id,Form form,String written,String reading) {
        var v=catalog.verb(id);assertEquals(written,conjugate(v,form,false));assertEquals(reading,conjugate(v,form,true));
    }
    @Test void acceptsKanaAndStandardShortCausativePassiveButRejectsWrongForms() {
        assertTrue(correct(catalog.verb("kaku"),Form.TE," カイテ "));
        assertTrue(correct(catalog.verb("kaku"),Form.CAUSATIVE_PASSIVE,"書かされる"));
        assertFalse(correct(catalog.verb("hanasu"),Form.CAUSATIVE_PASSIVE,"話さされる"));
        assertFalse(correct(catalog.verb("iku"),Form.TE,"行いて"));
        assertFalse(correct(catalog.verb("taberu"),Form.POTENTIAL,"食べれる"));
        assertFalse(correct(catalog.verb("kaku"),Form.TE,"か いて"));
        assertFalse(supports(catalog.verb("aru"),Form.POTENTIAL));
        assertFalse(supports(catalog.verb("wakaru"),Form.POTENTIAL));
    }
    @ParameterizedTest @CsvSource({
        "刷る,する,TE,刷って,すって", "齧る,かじる,TA,齧った,かじった",
        "敬う,うやまう,TE,敬って,うやまって", "強いる,しいる,TE,強いて,しいて",
        "経る,へる,TA,経た,へた", "欺く,あざむく,TA,欺いた,あざむいた",
        "稼ぐ,かせぐ,TA,稼いだ,かせいだ", "担う,になう,POTENTIAL,担える,になえる",
        "抑制する,よくせいする,NAI,抑制しない,よくせいしない"})
    void newVerbsUseExplicitGroupsAndCorrectStems(String word,String kana,Form form,String expected,String expectedKana) {
        var v=catalog.verbs().stream().filter(x->x.word().equals(word)&&x.reading().equals(kana)).findFirst().orElseThrow();
        assertEquals(expected,conjugate(v,form,false));assertEquals(expectedKana,conjugate(v,form,true));
    }
    @Test void everyCatalogContextAndSupportedFormProducesUsableAnswers() {
        assertTrue(catalog.verbs().size()>=500);assertTrue(catalog.contexts().size()>=70);
        for(var level:List.of("N5","N4","N3","N2","N1"))assertTrue(catalog.verbs().stream().filter(v->v.level().equals(level)).count()>=60);
        assertEquals(catalog.verbs().size(),catalog.verbs().stream().map(v->v.word()+"|"+v.reading()).distinct().count());
        for(var v:catalog.verbs())for(var f:Form.values())if(supports(v,f))assertFalse(conjugate(v,f,true).isBlank());
        for(var c:catalog.contexts()){assertTrue(supports(catalog.verb(c.verbId()),c.form()));assertEquals(1,c.sentence().split("___",-1).length-1);}
    }
}
