package com.jlas.dictionary.util;

import com.jlas.dictionary.util.VerbDeinflector.Candidate;
import com.jlas.dictionary.util.VerbDeinflector.Form;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;

class VerbDeinflectorTest {

    private static Optional<Candidate> find(String query, String term) {
        return VerbDeinflector.deinflect(query).stream().filter(c -> c.term().equals(term)).findFirst();
    }

    @ParameterizedTest(name = "{0} -> {1} ({2})")
    @CsvSource({
            // 行く: dạng liên dụng, ます, た/て bất quy tắc, phủ định, nguyện vọng.
            "行き, 行く, V5", "行きます, 行く, V5", "行きました, 行く, V5", "行きません, 行く, V5",
            "行きませんでした, 行く, V5", "行きましょう, 行く, V5", "行った, 行く, V5", "行って, 行く, V5",
            "行かない, 行く, V5", "行かなかった, 行く, V5", "行きたい, 行く, V5", "行きたくない, 行く, V5",
            "行ける, 行く, V5", "行こう, 行く, V5", "行けば, 行く, V5", "行っています, 行く, V5",
            // Nhóm I các hàng khác.
            "飲みました, 飲む, V5", "書いて, 書く, V5", "泳いだ, 泳ぐ, V5", "話した, 話す, V5", "待って, 待つ, V5",
            "死んだ, 死ぬ, V5", "遊んで, 遊ぶ, V5", "買った, 買う, V5", "帰らない, 帰る, V5",
            "飲ませられた, 飲む, V5", "書かれる, 書く, V5", "休み, 休む, V5",
            // Nhóm II.
            "食べ, 食べる, V1", "食べます, 食べる, V1", "食べた, 食べる, V1", "食べない, 食べる, V1",
            "食べませんでした, 食べる, V1", "食べている, 食べる, V1", "食べてる, 食べる, V1",
            "食べられる, 食べる, V1", "食べさせる, 食べる, V1", "食べよう, 食べる, V1", "見て, 見る, V1", "見ます, 見る, V1", "寝ました, 寝る, V1",
            // Nhóm III.
            "来ます, 来る, VK", "来た, 来る, VK", "来ない, 来る, VK", "きます, くる, VK", "こない, くる, VK",
            "します, する, VS", "した, する, VS", "勉強します, 勉強, VS", "勉強しました, 勉強する, VS",
            // Kana.
            "いきます, いく, V5", "たべます, たべる, V1", "のみました, のむ, V5",
            // Tính từ đuôi い.
            "高かった, 高い, ADJ_I", "高くない, 高い, ADJ_I", "高くて, 高い, ADJ_I", "高ければ, 高い, ADJ_I",
    })
    void reachesDictionaryForm(String query, String expected, Form form) {
        Optional<Candidate> candidate = find(query, expected);
        assertTrue(candidate.isPresent(), () -> query + " không ra " + expected + ": " + VerbDeinflector.deinflect(query));
        assertTrue(candidate.get().forms().contains(form), () -> expected + " thiếu " + form + ": " + candidate.get());
    }

    @Test
    void irregularPastOfIkuComesBeforeGenericGodanGuesses() {
        List<String> terms = VerbDeinflector.deinflect("行った").stream().map(Candidate::term).toList();
        // 行った cũng là quá khứ của 行う (おこなう); cả hai đều được giữ, 行く đứng trước.
        assertTrue(terms.contains("行う"), terms::toString);
        assertTrue(terms.indexOf("行く") < terms.indexOf("行う"), terms::toString);
    }

    @Test
    void dictionaryFormIsNotReturnedAsItsOwnCandidate() {
        assertTrue(find("食べる", "食べる").isEmpty());
        assertTrue(VerbDeinflector.deinflect("学校").isEmpty());
        assertTrue(VerbDeinflector.deinflect("").isEmpty());
    }

    @Test
    void stemOnlyFormsDoNotTurnIntoAdjectives() {
        // く (trạng từ) không được chia ngược: いく phải ra động từ, không ra いい.
        assertTrue(find("いく", "いい").isEmpty());
    }

    @Test
    void matchesImportedPartOfSpeechCodes() {
        assertTrue(Form.V5.matchesPartOfSpeech("v5k-s"));
        assertTrue(Form.V5.matchesPartOfSpeech("v5u"));
        assertTrue(Form.V1.matchesPartOfSpeech("v1"));
        assertTrue(Form.VS.matchesPartOfSpeech("vs"));
        assertTrue(Form.ADJ_I.matchesPartOfSpeech("adj-ix"));
        assertTrue(!Form.V5.matchesPartOfSpeech("n"));
        assertTrue(!Form.STEM.matchesPartOfSpeech("v1"));
        assertTrue(!Form.V1.matchesPartOfSpeech(null));
    }
}
