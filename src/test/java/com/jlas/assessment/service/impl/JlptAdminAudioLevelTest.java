package com.jlas.assessment.service.impl;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class JlptAdminAudioLevelTest {

    @Test void localPathOfSameLevelPasses() {
        assertThat(JlptAdminServiceImpl.audioLevelMismatch("/audio/JLPT/N3/N3_2012_07/Mondai1/1.mp3", "N3")).isNull();
    }

    @Test void localPathOfOtherLevelIsRejected() {
        assertThat(JlptAdminServiceImpl.audioLevelMismatch("/audio/JLPT/N3/N3_2012_07/Mondai1/1.mp3", "N1")).isEqualTo("N3");
    }

    @Test void mixedLevelsInsidePathAreRejected() {
        assertThat(JlptAdminServiceImpl.audioLevelMismatch("/audio/JLPT/N3/N1_2012_07/Mondai1/1.mp3", "N3")).isEqualTo("N1");
    }

    @Test void firebaseUrlUsesEncodedSlashes() {
        String url = "https://firebasestorage.googleapis.com/v0/b/x/o/audio%2FN1%2F2012-12-N1-mondai1.mp3?alt=media";
        assertThat(JlptAdminServiceImpl.audioLevelMismatch(url, "N1")).isNull();
        assertThat(JlptAdminServiceImpl.audioLevelMismatch(url, "N2")).isEqualTo("N1");
    }

    @Test void urlWithoutLevelTokenCannotBeCheckedSoPasses() {
        String url = "https://storage.googleapis.com/x/listening/mondai-1888-audio-9966bc1f-eba1-4913-be6b-25d59.mp3";
        assertThat(JlptAdminServiceImpl.audioLevelMismatch(url, "N4")).isNull();
    }

    @Test void levelLettersInsideWordsAreIgnored() {
        assertThat(JlptAdminServiceImpl.audioLevelMismatch("/audio/JLPT/N2/LN3X/clip.mp3", "N2")).isNull();
    }
}
