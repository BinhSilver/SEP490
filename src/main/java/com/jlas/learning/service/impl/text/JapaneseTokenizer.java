package com.jlas.learning.service.impl.text;

import com.atilika.kuromoji.ipadic.Token;
import com.atilika.kuromoji.ipadic.Tokenizer;

import java.util.List;

/** One kuromoji (IPADIC) tokenizer for Learning: its dictionary is large, so every caller shares this instance. */
public final class JapaneseTokenizer {
    private static final Tokenizer TOKENIZER = new Tokenizer();

    private JapaneseTokenizer() { }

    public static List<Token> tokenize(String text) {
        return TOKENIZER.tokenize(text);
    }
}
