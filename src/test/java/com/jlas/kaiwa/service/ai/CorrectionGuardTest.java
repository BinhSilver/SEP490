package com.jlas.kaiwa.service.ai;

import com.jlas.kaiwa.dto.AiModelReply;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CorrectionGuardTest {

    private static AiModelReply correction(String original, String corrected) {
        AiModelReply reply = new AiModelReply();
        reply.setReply("いいですね。");
        reply.setHasError(true);
        reply.setOriginalWrong(original);
        reply.setCorrected(corrected);
        reply.setGrammarReason("lý do");
        reply.setMistakeType("grammar");
        return reply;
    }

    @Test
    void correctionThatReplacesTheLearnersTimeWordIsDropped() {
        String learner = "あした、ともだちといっしょにえいがをみにいきます。";
        AiModelReply reply = correction(learner, "あさ、ともだちといっしょにえいがをみにいきます。");

        AIConversationService.discardUnchangedCorrection(reply, learner);

        assertThat(reply.isHasError()).isFalse();
        assertThat(reply.getCorrected()).isNull();
        assertThat(reply.getGrammarReason()).isNull();
    }

    @Test
    void realParticleFixIsKept() {
        AiModelReply reply = correction("学校を行きました。", "学校へ行きました。");

        AIConversationService.discardUnchangedCorrection(reply, "学校を行きました。");

        assertThat(reply.isHasError()).isTrue();
        assertThat(reply.getCorrected()).isEqualTo("学校へ行きました。");
    }

    @Test
    void timeWordMayChangeScriptAndTenseMayBeFixed() {
        assertThat(AIConversationService.dropsTimeWord("きのう えいがを みます", "昨日、映画を見ました")).isFalse();
        assertThat(AIConversationService.dropsTimeWord("明日 行きました", "あした行きます")).isFalse();
        // きょう inside べんきょう is not a time word.
        assertThat(AIConversationService.dropsTimeWord("にほんごをべんきょうをします", "日本語を勉強します")).isFalse();
        assertThat(AIConversationService.dropsTimeWord("今日はさむいです", "きょうはさむいです")).isFalse();
        assertThat(AIConversationService.dropsTimeWord("今日はさむいです", "昨日はさむかったです")).isTrue();
    }

    @Test
    void unchangedQuoteOfOneSentenceInALongerMessageIsDropped() {
        String learner = "はい、元気です。今日は日本語を勉強しました。";
        AiModelReply quoted = correction("今日は日本語を勉強しました。", "今日は日本語を勉強しました。");
        AiModelReply noOriginal = correction(null, "今日は日本語を勉強しました");

        AIConversationService.discardUnchangedCorrection(quoted, learner);
        AIConversationService.discardUnchangedCorrection(noOriginal, learner);

        assertThat(quoted.isHasError()).isFalse();
        assertThat(noOriginal.isHasError()).isFalse();
    }

    @Test
    void realFixOfOneSentenceInALongerMessageIsKept() {
        AiModelReply reply = correction("学校を行きました。", "学校へ行きました。");

        AIConversationService.discardUnchangedCorrection(reply, "はい、元気です。学校を行きました。");

        assertThat(reply.isHasError()).isTrue();
    }

    @Test
    void correctionIdenticalToLearnerSentenceIsStillDropped() {
        AiModelReply reply = correction("はい、元気です", "はい、元気です。");

        AIConversationService.discardUnchangedCorrection(reply, "はい、元気です");

        assertThat(reply.isHasError()).isFalse();
    }
}
