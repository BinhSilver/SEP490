package com.jlas.jlptpath.dto;

import com.jlas.jlptpath.enums.JpathStudyMode;
import java.time.LocalDate;
import java.util.List;

/**
 * [JLAS-W0108] Changes on the settings page; every field null = keep. Schedule block (needs an ACTIVE path):
 * {@code targetLevel}, {@code examDate}, {@code clearExamDate}, {@code dailyMinutes}, {@code skillDays},
 * {@code restDays}, {@code thirdSkillDay} ("" = chosen automatically), {@code studyMode}. Display and reminder block:
 * the rest; {@code romajiMode} AUTO|ON|OFF, {@code fontSize} S|M|L, {@code vocabListView} TOPICS|FLAT,
 * {@code reminderTime} HH:mm in 5-minute steps, {@code tourDone}/{@code swipeHintDone} true = mark as seen.
 * Owner 22/11 (F230): {@code vocabPerDay} = new words per day (schedule block); Kanji and grammar follow it and the
 * minutes become the option that holds that day.
 */
public record JpathSettingsUpdateRequest(String targetLevel, LocalDate examDate, Boolean clearExamDate,
        Integer dailyMinutes, List<String> skillDays, List<String> restDays, String thirdSkillDay,
        JpathStudyMode studyMode, Boolean furiganaHiddenAll, String romajiMode, String fontSize, Boolean soundOn,
        Boolean autoplayAudio, Boolean quizTimerOn, Boolean grammarTimerOn, Boolean reminderWeb,
        Boolean reminderEmail, String reminderTime, String vocabListView, Boolean tourDone, Boolean swipeHintDone,
        Integer vocabPerDay) {

    public static JpathSettingsUpdateRequest pace(int words) {
        return new JpathSettingsUpdateRequest(null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, words);
    }

    /** Request without {@code vocabPerDay}. */
    public JpathSettingsUpdateRequest(String targetLevel, LocalDate examDate, Boolean clearExamDate,
            Integer dailyMinutes, List<String> skillDays, List<String> restDays, String thirdSkillDay,
            JpathStudyMode studyMode, Boolean furiganaHiddenAll, String romajiMode, String fontSize, Boolean soundOn,
            Boolean autoplayAudio, Boolean quizTimerOn, Boolean grammarTimerOn, Boolean reminderWeb,
            Boolean reminderEmail, String reminderTime, String vocabListView, Boolean tourDone, Boolean swipeHintDone) {
        this(targetLevel, examDate, clearExamDate, dailyMinutes, skillDays, restDays, thirdSkillDay, studyMode,
                furiganaHiddenAll, romajiMode, fontSize, soundOn, autoplayAudio, quizTimerOn, grammarTimerOn,
                reminderWeb, reminderEmail, reminderTime, vocabListView, tourDone, swipeHintDone, null);
    }

    /** Whether any field of the schedule block is given. */
    public boolean changesSchedule() {
        return targetLevel != null || examDate != null || Boolean.TRUE.equals(clearExamDate) || dailyMinutes != null
                || vocabPerDay != null || skillDays != null || restDays != null || thirdSkillDay != null || studyMode != null;
    }
}
