package com.jlas.kaiwa.service.understanding;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.kaiwa.service.learning.JapaneseReadingService;
import com.jlas.kaiwa.util.JapaneseText;
import java.text.Normalizer;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Local input gate; runs before quota reservation, transcript writes, and the AI call. */
@Component
public class JapaneseChatInputValidator {
    private static final Pattern STUDY_CODES = Pattern.compile(
            "(?<![A-Za-z0-9])(?:JLPT|AI|N[1-5])(?![A-Za-z0-9])");
    private final JapaneseReadingService readings;

    public JapaneseChatInputValidator(JapaneseReadingService readings) { this.readings = readings; }

    public void validate(String text) {
        if (text == null || text.isBlank()) reject();
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC);
        String content = STUDY_CODES.matcher(normalized).replaceAll("");
        boolean kana = false;
        boolean han = false;
        for (int cp : content.codePoints().toArray()) {
            var script = Character.UnicodeScript.of(cp);
            if (script == Character.UnicodeScript.HIRAGANA || script == Character.UnicodeScript.KATAKANA) {
                kana = true;
            } else if (script == Character.UnicodeScript.HAN) {
                han = true;
            } else if (Character.isLetter(cp) && cp != 'ー') {
                reject();
            }
            int type = Character.getType(cp);
            if ((type == Character.CONTROL && !Character.isWhitespace(cp))
                    || (type == Character.FORMAT && cp != 0x200D)) reject();
        }
        if (!kana && !han) reject();
        if (!kana) {
            // Han is shared with Chinese. Only a recognized Japanese dictionary word is accepted
            // without kana; ambiguous multi-word passages must be written as a Japanese sentence.
            var words = readings.annotate(content).stream()
                    .filter(token -> JapaneseText.hasKanji(token.getSurface())).toList();
            if (words.size() != 1 || !words.getFirst().isKanjiReadingComplete()) reject();
        }
    }

    private static void reject() { throw new ApiException(ErrorCode.AICALL_JAPANESE_REQUIRED); }
}
