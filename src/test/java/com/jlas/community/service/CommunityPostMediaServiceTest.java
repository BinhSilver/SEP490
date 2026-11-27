package com.jlas.community.service;

import com.jlas.common.exception.ApiException;
import com.jlas.community.repository.CommunityPostMediaRepository;
import com.jlas.moderation.dto.ModerationResult;
import com.jlas.moderation.model.ModerationVerdict;
import com.jlas.moderation.service.ContentModerationService;
import com.jlas.shadowing.dto.SttResult;
import com.jlas.shadowing.dto.YoutubeAnalyzeResultDto;
import com.jlas.shadowing.service.DeepgramService;
import com.jlas.shadowing.service.FfmpegService;
import com.jlas.shadowing.service.ShadowingBlobStore;
import com.jlas.shadowing.service.YoutubeAnalyzeService;
import com.jlas.shadowing.service.YoutubeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CommunityPostMediaServiceTest {

    private static final String BODY = "Mọi người ơi, ngữ pháp ～ている dùng thế nào?";
    private static final ModerationResult SAFE = ModerationResult.builder().verdict(ModerationVerdict.SAFE).build();

    private final CommunityPostMediaRepository repo = mock(CommunityPostMediaRepository.class);
    private final ShadowingBlobStore blobs = mock(ShadowingBlobStore.class);
    private final ContentModerationService moderation = mock(ContentModerationService.class);
    private final YoutubeAnalyzeService youtube = mock(YoutubeAnalyzeService.class);
    private final YoutubeService youtubeDownloads = mock(YoutubeService.class);
    private final FfmpegService ffmpeg = mock(FfmpegService.class);
    private final DeepgramService deepgram = mock(DeepgramService.class);
    private final CommunityPostMediaService service = new CommunityPostMediaService(repo, blobs, moderation, youtube,
            youtubeDownloads, ffmpeg, deepgram);

    @BeforeEach
    void moderationPassesByDefault() throws Exception {
        when(moderation.scanText(anyString())).thenReturn(SAFE);
        when(moderation.scanJapaneseTopic(anyString(), anyList())).thenReturn(SAFE);
        when(moderation.scanYoutubeRelated(anyString())).thenReturn(SAFE);
        when(youtube.analyze(anyString())).thenReturn(video("日本語の勉強", "ja"));
    }

    /** Video YouTube; {@code spoken} = ngôn ngữ lời thoại gốc (null = không rõ). */
    private static YoutubeAnalyzeResultDto video(String title, String spoken) {
        YoutubeAnalyzeResultDto meta = new YoutubeAnalyzeResultDto();
        meta.setTitle(title);
        meta.setChannel("Kênh A");
        meta.setSpokenLanguage(spoken);
        meta.setDuration(180);
        // YouTube tự dịch phụ đề sang tiếng Nhật cho hầu hết video: không được dùng để xét.
        meta.setJapaneseSubtitleLang("ja");
        return meta;
    }

    private void unknownLanguageVideoSays(String transcript) throws Exception {
        when(youtube.analyze(anyString())).thenReturn(video("Video #12", null));
        when(youtubeDownloads.downloadAudio(anyString())).thenReturn("yt_audio.mp3");
        SttResult stt = new SttResult();
        stt.setTranscript(transcript);
        when(deepgram.transcribeWavFile(any(), anyDouble())).thenReturn(stt);
    }

    private static ModerationResult unrelated(String reason) {
        return ModerationResult.builder().verdict(ModerationVerdict.BLOCKED).summary(reason).build();
    }

    @Test
    void offTopicPostIsRejectedWithReason() {
        when(moderation.scanJapaneseTopic(eq("gg"), anyList())).thenReturn(ModerationResult.builder()
                .verdict(ModerationVerdict.BLOCKED).summary("Nội dung không có ý nghĩa").build());
        assertThatThrownBy(() -> service.prepare("gg", null, null, null))
                .isInstanceOf(ApiException.class).hasMessageContaining("liên quan tới tiếng Nhật")
                .hasMessageContaining("Nội dung không có ý nghĩa.");
    }

    @Test
    void deniedWordsAreRejectedBeforeTopicCheck() {
        when(moderation.scanText("xấu")).thenReturn(ModerationResult.builder().verdict(ModerationVerdict.BLOCKED).build());
        assertThatThrownBy(() -> service.prepare("xấu", null, null, null)).isInstanceOf(ApiException.class);
        verify(moderation, never()).scanJapaneseTopic(anyString(), anyList());
    }

    @Test
    void japaneseSpokenVideoIsRelatedWithoutAskingAi() throws Exception {
        service.prepare(BODY, null, null, "https://youtu.be/dQw4w9WgXcQ");
        verify(moderation, never()).scanYoutubeRelated(anyString());
        verify(moderation).scanJapaneseTopic(eq(BODY), argThat(context -> context.size() == 1
                && context.get(0).contains("日本語の勉強") && context.get(0).contains("Tiếng Nhật")));
        verifyNoInteractions(youtubeDownloads, deepgram);
    }

    @Test
    void unrelatedVideoInAnotherLanguageIsRejected() throws Exception {
        YoutubeAnalyzeResultDto meta = video("Trường Giang nấu Bún Bò Huế nóng hổi cho ngày mưa to", "vi");
        meta.setDescription("Vào bếp cùng Giang");
        when(youtube.analyze(anyString())).thenReturn(meta);
        when(moderation.scanYoutubeRelated(argThat(info -> info.contains("Bún Bò Huế") && info.contains("Tiếng Việt")
                && info.contains("Vào bếp cùng Giang")))).thenReturn(unrelated("Video là vlog nấu ăn, không liên quan tiếng Nhật"));
        assertThatThrownBy(() -> service.prepare(BODY, null, null, "https://youtu.be/dQw4w9WgXcQ"))
                .isInstanceOf(ApiException.class).hasMessageContaining("liên quan tới tiếng Nhật")
                .hasMessageContaining("vlog nấu ăn");
        verify(moderation, never()).scanJapaneseTopic(anyString(), anyList());
    }

    @Test
    void japaneseLessonExplainedInVietnameseIsAllowed() throws Exception {
        when(youtube.analyze(anyString())).thenReturn(video("Ngữ pháp Minna 1 - Bài 1 - Học Tiếng Nhật Minna No Nihongo", "vi"));
        service.prepare(BODY, null, null, "https://youtu.be/dQw4w9WgXcQ");
        verify(moderation).scanYoutubeRelated(argThat(info -> info.contains("Minna") && info.contains("Tiếng Việt")));
        verify(moderation).scanJapaneseTopic(eq(BODY), anyList());
    }

    @Test
    void unsureOrFailedAiBlocksANonJapaneseVideo() throws Exception {
        when(youtube.analyze(anyString())).thenReturn(video("Never Gonna Give You Up", "en"));
        when(moderation.scanYoutubeRelated(anyString())).thenReturn(unrelated("Chưa kiểm tra được nội dung video, hãy thử lại sau."));
        assertThatThrownBy(() -> service.prepare(BODY, null, null, "https://youtu.be/dQw4w9WgXcQ"))
                .isInstanceOf(ApiException.class).hasMessageContaining("Chưa kiểm tra được");
    }

    @Test
    void unknownLanguageVideoSpeakingJapaneseIsRelated() throws Exception {
        unknownLanguageVideoSays("こんにちは、今日は日本語の勉強をしましょう。よろしくお願いします。");
        service.prepare(BODY, null, null, "https://youtu.be/dQw4w9WgXcQ");
        verify(moderation, never()).scanYoutubeRelated(anyString());
        verify(moderation).scanJapaneseTopic(eq(BODY), argThat(context -> context.get(0).contains("こんにちは")));
    }

    @Test
    void unknownLanguageVideoSpeakingVietnameseGoesToAiWithTheTranscript() throws Exception {
        unknownLanguageVideoSays("Hôm nay mình sẽ nấu món bún bò Huế thật ngon cho cả nhà cùng ăn nhé các bạn");
        when(moderation.scanYoutubeRelated(argThat(info -> info.contains("bún bò Huế"))))
                .thenReturn(unrelated("Video là vlog nấu ăn"));
        assertThatThrownBy(() -> service.prepare(BODY, null, null, "https://youtu.be/dQw4w9WgXcQ"))
                .isInstanceOf(ApiException.class).hasMessageContaining("vlog nấu ăn");
    }

    @Test
    void longUnknownLanguageVideoIsJudgedWithoutDownloadingAudio() throws Exception {
        YoutubeAnalyzeResultDto meta = video("Video #12", null);
        meta.setDuration(CommunityPostMediaService.SPEECH_SAMPLE_MAX_SECONDS + 1);
        when(youtube.analyze(anyString())).thenReturn(meta);
        service.prepare(BODY, null, null, "https://youtu.be/dQw4w9WgXcQ");
        verifyNoInteractions(youtubeDownloads);
        verify(moderation).scanYoutubeRelated(argThat(info -> info.contains("chưa xác định")));
    }

    @Test
    void youtubeThatCannotBeAnalyzedIsRejectedLikeShadowing() throws Exception {
        when(youtube.analyze(anyString())).thenThrow(new IllegalStateException("Sign in to confirm you're not a bot"));
        assertThatThrownBy(() -> service.prepare(BODY, null, null, "https://youtu.be/dQw4w9WgXcQ"))
                .isInstanceOf(ApiException.class).hasMessageContaining("Không phân tích được video YouTube");
        verify(moderation, never()).scanJapaneseTopic(anyString(), anyList());
    }

    @Test
    void notEmbeddableYoutubeIsRejectedLikeShadowing() throws Exception {
        YoutubeAnalyzeResultDto meta = video("日本語の勉強", "ja");
        meta.setEmbeddable(false);
        when(youtube.analyze(anyString())).thenReturn(meta);
        assertThatThrownBy(() -> service.prepare(BODY, null, null, "https://youtu.be/dQw4w9WgXcQ"))
                .isInstanceOf(ApiException.class).hasMessageContaining("bản quyền");
    }

    @Test
    void youtubeIdFromCommonLinks() {
        assertThat(CommunityPostMediaService.youtubeId("https://www.youtube.com/watch?v=dQw4w9WgXcQ")).isEqualTo("dQw4w9WgXcQ");
        assertThat(CommunityPostMediaService.youtubeId("https://youtu.be/dQw4w9WgXcQ?t=42")).isEqualTo("dQw4w9WgXcQ");
        assertThat(CommunityPostMediaService.youtubeId("youtube.com/shorts/dQw4w9WgXcQ")).isEqualTo("dQw4w9WgXcQ");
        assertThat(CommunityPostMediaService.youtubeId("https://m.youtube.com/watch?list=x&v=dQw4w9WgXcQ")).isEqualTo("dQw4w9WgXcQ");
        assertThat(CommunityPostMediaService.youtubeId("https://evil.com/watch?v=dQw4w9WgXcQ")).isNull();
        assertThat(CommunityPostMediaService.youtubeId("https://www.youtube.com/watch?v=short")).isNull();
    }

    @Test
    void textOnlyPostStoresNothing() {
        assertThat(service.prepare(BODY, null, null, " ")).isEmpty();
        verifyNoInteractions(blobs);
    }

    @Test
    void youtubeLinkNeedsNoStorage() {
        List<CommunityPostMediaService.Stored> stored = service.prepare(BODY, null, null, "https://youtu.be/dQw4w9WgXcQ");
        assertThat(stored).singleElement().satisfies(s -> {
            assertThat(s.kind()).isEqualTo("YOUTUBE");
            assertThat(s.storageKey()).isEqualTo("dQw4w9WgXcQ");
        });
        verifyNoInteractions(blobs);
    }

    @Test
    void rejectsBadYoutubeLink() {
        assertThatThrownBy(() -> service.prepare(BODY, null, null, "https://vimeo.com/123")).isInstanceOf(ApiException.class);
    }

    @Test
    void rejectsMoreThanFourImages() {
        MultipartFile img = new MockMultipartFile("images", "a.png", "image/png", new byte[]{1});
        assertThatThrownBy(() -> service.prepare(BODY, Collections.nCopies(5, img), null, null))
                .isInstanceOf(ApiException.class).hasMessageContaining("4");
    }

    @Test
    void rejectsVideoAndYoutubeTogether() {
        MultipartFile video = mp4();
        assertThatThrownBy(() -> service.prepare(BODY, null, video, "https://youtu.be/dQw4w9WgXcQ"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void videoMustBeRealMp4OrWebm() {
        assertThat(CommunityPostMediaService.validateVideo(mp4())).isEqualTo("mp4");
        MultipartFile fake = new MockMultipartFile("video", "a.mp4", "video/mp4", "not a video file".getBytes());
        assertThatThrownBy(() -> CommunityPostMediaService.validateVideo(fake)).isInstanceOf(ApiException.class);
        MultipartFile avi = new MockMultipartFile("video", "a.avi", "video/x-msvideo", new byte[16]);
        assertThatThrownBy(() -> CommunityPostMediaService.validateVideo(avi)).isInstanceOf(ApiException.class);
    }

    @Test
    void filesNeedConfiguredStorage() {
        when(blobs.configured()).thenReturn(false);
        assertThatThrownBy(() -> service.prepare(BODY, null, mp4(), null)).isInstanceOf(ApiException.class);
    }

    private static MultipartFile mp4() {
        byte[] head = {0, 0, 0, 24, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm', 0, 0, 0, 0};
        return new MockMultipartFile("video", "clip.mp4", "video/mp4", head);
    }
}
