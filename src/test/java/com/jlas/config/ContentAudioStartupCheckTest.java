package com.jlas.config;

import com.jlas.dictionary.repository.ContentAudioRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContentAudioStartupCheckTest {
    @TempDir Path dir;
    ContentAudioRepository repository = mock(ContentAudioRepository.class);

    ContentAudioStartupCheck checker(Path root) {
        return new ContentAudioStartupCheck(repository, root.toString(), true);
    }

    void touch(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, "x");
    }

    @Test void allFilesPresent() throws IOException {
        touch(dir.resolve("vnjpclub/N3/a.mp3"));
        touch(dir.resolve("sekai-n5/b.mp3"));
        when(repository.findDistinctAudioUrls()).thenReturn(List.of("vnjpclub/N3/a.mp3", "sekai-n5/b.mp3"));
        var result = checker(dir).check();
        assertThat(result.missingCount()).isZero();
        assertThat(result.nestedRoot()).isNull();
    }

    @Test void missingDirectoryReportsEveryFile() {
        when(repository.findDistinctAudioUrls()).thenReturn(List.of("vnjpclub/N3/a.mp3", "vnjpclub/N1/b.mp3"));
        var result = checker(dir.resolve("khong-co")).check();
        assertThat(result.missingCount()).isEqualTo(2);
        assertThat(result.missingSamples()).containsExactly("vnjpclub/N3/a.mp3", "vnjpclub/N1/b.mp3");
    }

    @Test void missingFilesAreCounted() throws IOException {
        touch(dir.resolve("vnjpclub/N3/a.mp3"));
        when(repository.findDistinctAudioUrls()).thenReturn(List.of("vnjpclub/N3/a.mp3", "vnjpclub/N3/thieu.mp3"));
        var result = checker(dir).check();
        assertThat(result.missingCount()).isEqualTo(1);
        assertThat(result.missingSamples()).containsExactly("vnjpclub/N3/thieu.mp3");
        assertThat(result.nestedRoot()).isNull();
    }

    @Test void detectsExtraNestedFolderFromUnzip() throws IOException {
        touch(dir.resolve("jlas-audio/vnjpclub/N3/a.mp3"));
        when(repository.findDistinctAudioUrls()).thenReturn(List.of("vnjpclub/N3/a.mp3"));
        var result = checker(dir).check();
        assertThat(result.missingCount()).isEqualTo(1);
        assertThat(result.nestedRoot()).isEqualTo(dir.resolve("jlas-audio").toAbsolutePath().normalize());
    }

    @Test void pathsEscapingTheRootCountAsMissing() throws IOException {
        Path root = dir.resolve("kho");
        Files.createDirectories(root);
        touch(dir.resolve("ngoai.mp3"));
        when(repository.findDistinctAudioUrls()).thenReturn(List.of("../ngoai.mp3"));
        assertThat(checker(root).check().missingCount()).isEqualTo(1);
    }

    void pointer(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, "version https://git-lfs.github.com/spec/v1\n"
                + "oid sha256:4d7a214614ab2935c943f9e0ff69d22eadbb8f32b1258daaa5e2ca24d17e2393\nsize 315602\n");
    }

    void realAudio(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[4096]);
    }

    @Test void allLfsPointersAreReportedWithoutThrowing() throws IOException {
        pointer(dir.resolve("vnjpclub/N3/a.mp3"));
        pointer(dir.resolve("sekai-n5/b.mp3"));
        when(repository.findDistinctAudioUrls()).thenReturn(List.of("vnjpclub/N3/a.mp3", "sekai-n5/b.mp3"));
        var result = checker(dir).check();
        assertThat(result.lfsPointerSamples()).isEqualTo(2);
        assertThat(result.missingCount()).isZero();
    }

    @Test void realAudioIsNotAPointer() throws IOException {
        realAudio(dir.resolve("vnjpclub/N3/a.mp3"));
        when(repository.findDistinctAudioUrls()).thenReturn(List.of("vnjpclub/N3/a.mp3"));
        assertThat(checker(dir).check().lfsPointerSamples()).isZero();
    }

    @Test void mixedPointersAndRealAudioCountOnlyPointers() throws IOException {
        realAudio(dir.resolve("vnjpclub/N3/a.mp3"));
        pointer(dir.resolve("vnjpclub/N3/b.mp3"));
        touch(dir.resolve("vnjpclub/N3/c.mp3"));
        when(repository.findDistinctAudioUrls())
                .thenReturn(List.of("vnjpclub/N3/a.mp3", "vnjpclub/N3/b.mp3", "vnjpclub/N3/c.mp3"));
        assertThat(checker(dir).check().lfsPointerSamples()).isEqualTo(1);
    }

    @Test void pointerSampleIsCappedAtFifty() throws IOException {
        List<String> urls = new java.util.ArrayList<>();
        for (int i = 0; i < 120; i++) {
            pointer(dir.resolve("sekai-n5/" + i + ".mp3"));
            urls.add("sekai-n5/" + i + ".mp3");
        }
        when(repository.findDistinctAudioUrls()).thenReturn(urls);
        assertThat(checker(dir).check().lfsPointerSamples()).isEqualTo(ContentAudioStartupCheck.LFS_SAMPLE_LIMIT);
    }

    @Test void emptyTableSkipsCheck() {
        when(repository.findDistinctAudioUrls()).thenReturn(List.of());
        assertThat(checker(dir.resolve("khong-co")).check().missingCount()).isZero();
    }
}
