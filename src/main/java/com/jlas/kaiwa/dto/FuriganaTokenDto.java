package com.jlas.kaiwa.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.jlas.kaiwa.util.JapaneseText;

public class FuriganaTokenDto {

    private String surface;
    private String reading;
    private String meaning;
    private String partOfSpeech;
    private String jlptLevel;
    private boolean fromVocabDb;

    public FuriganaTokenDto() {}

    public FuriganaTokenDto(String surface, String reading) {
        this.surface = surface;
        this.reading = reading;
    }

    public String getSurface() { return surface; }
    public void setSurface(String surface) { this.surface = surface; }
    public String getReading() { return reading; }
    public void setReading(String reading) { this.reading = reading; }
    public String getMeaning() { return meaning; }
    public void setMeaning(String meaning) { this.meaning = meaning; }
    public String getPartOfSpeech() { return partOfSpeech; }
    public void setPartOfSpeech(String partOfSpeech) { this.partOfSpeech = partOfSpeech; }
    public String getJlptLevel() { return jlptLevel; }
    public void setJlptLevel(String jlptLevel) { this.jlptLevel = jlptLevel; }
    public boolean isFromVocabDb() { return fromVocabDb; }
    public void setFromVocabDb(boolean fromVocabDb) { this.fromVocabDb = fromVocabDb; }

    @JsonIgnore
    public boolean isRuby() {
        return JapaneseText.hasKanji(surface)
                && !JapaneseText.hiraganaReading(reading).isEmpty();
    }

    @JsonIgnore
    public boolean isKanjiReadingComplete() {
        return !JapaneseText.hasKanji(surface)
                || !JapaneseText.hiraganaReading(reading).isEmpty();
    }
}
