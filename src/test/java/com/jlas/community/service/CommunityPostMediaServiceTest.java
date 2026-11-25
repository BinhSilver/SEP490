package com.jlas.community.service;

import com.jlas.common.exception.ApiException;
import com.jlas.community.repository.CommunityPostMediaRepository;
import com.jlas.moderation.dto.ModerationResult;
import com.jlas.moderation.model.ModerationVerdict;
import com.jlas.moderation.service.ContentModerationService;
import com.jlas.shadowing.dto.YoutubeAnalyzeResultDto;
import com.jlas.shadowing.service.DeepgramService;
import com.jlas.shadowing.service.FfmpegService;
import com.jlas.shadowing.service.ShadowingBlobStore;
import com.jlas.shadowing.service.YoutubeAnalyzeService;
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
    private final CommunityPostMediaService service = new CommunityPostMediaService(repo, blobs, moderation, youtube,
            mock(FfmpegService.class), mock(DeepgramService.class));

    @BeforeEach
    void moderationPassesByDefault() throws Exception {
        when(moderation.scanText(anyString())).thenReturn(SAFE);
        when(moderation.scanJapaneseTopic(anyString(), anyList())).thenReturn(SAFE);
        when(youtube.analyze(anyString())).thenThrow(new IllegalStateException("yt-dlp unavailable"));
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
    void nonJapaneseYoutubeVideoIsRejectedLikeShadowing() throws Exception {
        YoutubeAnalyzeResultDto meta = new YoutubeAnalyzeResultDto();
        meta.setBlockedNonJapanese(true);
        meta.setLanguageMessage("Video được nhận diện là Tiếng Việt. KotsuKotsu chỉ nhận nội dung tiếng Nhật.");
        doReturn(meta).when(youtube).analyze(anyString());
        assertThatThrownBy(() -> service.prepare(BODY, null, null, "https://youtu.be/dQw4w9WgXcQ"))
                .isInstanceOf(ApiException.class).hasMessageContaining("Tiếng Việt");
    }

    @Test
    void japaneseYoutubeVideoTitleIsGivenToTopicCheck() throws Exception {
        YoutubeAnalyzeResultDto meta = new YoutubeAnalyzeResultDto();
        meta.setTitle("日本語の勉強");
        meta.setDetectedLanguageLabel("Tiếng Nhật");
        doReturn(meta).when(youtube).analyze(anyString());
        service.prepare(BODY, null, null, "https://youtu.be/dQw4w9WgXcQ");
        verify(moderation).scanJapaneseTopic(eq(BODY), argThat(context -> context.size() == 1
                && context.get(0).contains("日本語の勉強") && context.get(0).contains("Tiếng Nhật")));
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
