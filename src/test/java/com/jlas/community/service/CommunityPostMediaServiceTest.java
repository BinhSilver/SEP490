package com.jlas.community.service;

import com.jlas.common.exception.ApiException;
import com.jlas.community.repository.CommunityPostMediaRepository;
import com.jlas.shadowing.service.ShadowingBlobStore;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class CommunityPostMediaServiceTest {

    private final CommunityPostMediaRepository repo = mock(CommunityPostMediaRepository.class);
    private final ShadowingBlobStore blobs = mock(ShadowingBlobStore.class);
    private final CommunityPostMediaService service = new CommunityPostMediaService(repo, blobs);

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
        assertThat(service.prepare(null, null, " ")).isEmpty();
        verifyNoInteractions(blobs);
    }

    @Test
    void youtubeLinkNeedsNoStorage() {
        List<CommunityPostMediaService.Stored> stored = service.prepare(null, null, "https://youtu.be/dQw4w9WgXcQ");
        assertThat(stored).singleElement().satisfies(s -> {
            assertThat(s.kind()).isEqualTo("YOUTUBE");
            assertThat(s.storageKey()).isEqualTo("dQw4w9WgXcQ");
        });
        verifyNoInteractions(blobs);
    }

    @Test
    void rejectsBadYoutubeLink() {
        assertThatThrownBy(() -> service.prepare(null, null, "https://vimeo.com/123")).isInstanceOf(ApiException.class);
    }

    @Test
    void rejectsMoreThanFourImages() {
        MultipartFile img = new MockMultipartFile("images", "a.png", "image/png", new byte[]{1});
        assertThatThrownBy(() -> service.prepare(Collections.nCopies(5, img), null, null))
                .isInstanceOf(ApiException.class).hasMessageContaining("4");
    }

    @Test
    void rejectsVideoAndYoutubeTogether() {
        MultipartFile video = mp4();
        assertThatThrownBy(() -> service.prepare(null, video, "https://youtu.be/dQw4w9WgXcQ"))
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
        assertThatThrownBy(() -> service.prepare(null, mp4(), null)).isInstanceOf(ApiException.class);
    }

    private static MultipartFile mp4() {
        byte[] head = {0, 0, 0, 24, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm', 0, 0, 0, 0};
        return new MockMultipartFile("video", "clip.mp4", "video/mp4", head);
    }
}
