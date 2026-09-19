package com.jlas.dictionary.handwriting;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.dictionary.entity.Kanji;
import com.jlas.dictionary.repository.KanjiRepository;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KanjiStrokeIndexTest {

    private static Kanji kanji(String character, String uri) {
        return Kanji.builder().character(character).strokeSvgUri(uri).build();
    }

    /** Loader ánh xạ /assets/kanjivg/kanji/<hex>.svg sang fixture trong src/test/resources/kanjivg. */
    private static ResourceLoader fixtureLoader() {
        ResourceLoader loader = mock(ResourceLoader.class);
        when(loader.getResource(org.mockito.ArgumentMatchers.anyString())).thenAnswer(invocation -> {
            String location = invocation.getArgument(0);
            Resource resource = new ClassPathResource("kanjivg/" + location.substring(location.lastIndexOf('/') + 1));
            return resource;
        });
        return loader;
    }

    @Test
    void buildsOneReferencePerKanjiFromTheSvgTheDatabasePointsTo() {
        KanjiRepository repository = mock(KanjiRepository.class);
        when(repository.findAll()).thenReturn(List.of(
                kanji("日", "/assets/kanjivg/kanji/065e5.svg"),
                kanji("作", "/assets/kanjivg/kanji/04f5c.svg")));

        List<StrokeMatcher.Ref> refs = new KanjiStrokeIndex(repository, fixtureLoader()).refs();

        assertEquals(List.of("日", "作"), refs.stream().map(StrokeMatcher.Ref::character).toList());
        assertEquals(4, refs.get(0).strokes().length);
        assertEquals(7, refs.get(1).strokes().length);
    }

    @Test
    void isBuiltOnceAndCached() {
        KanjiRepository repository = mock(KanjiRepository.class);
        when(repository.findAll()).thenReturn(List.of(kanji("日", "/assets/kanjivg/kanji/065e5.svg")));
        KanjiStrokeIndex index = new KanjiStrokeIndex(repository, fixtureLoader());

        assertSame(index.refs(), index.refs());
        verify(repository, times(1)).findAll();
    }

    @Test
    void missingAssetsFailLoudlyWith503AndAreNotCached() {
        KanjiRepository repository = mock(KanjiRepository.class);
        when(repository.findAll()).thenReturn(List.of(kanji("日", "/assets/kanjivg/kanji/does-not-exist.svg")));
        KanjiStrokeIndex index = new KanjiStrokeIndex(repository, fixtureLoader());

        ApiException first = assertThrows(ApiException.class, index::refs);
        assertEquals(ErrorCode.SERVICE_UNAVAILABLE, first.getErrorCode());

        // Sau khi khôi phục dữ liệu, lần gọi sau phải dựng lại được (không kẹt kết quả rỗng).
        when(repository.findAll()).thenReturn(List.of(kanji("日", "/assets/kanjivg/kanji/065e5.svg")));
        assertEquals(1, index.refs().size());
        verify(repository, times(2)).findAll();
    }

    @Test
    void anEmptyKanjiTableIsAlsoUnavailable() {
        KanjiRepository repository = mock(KanjiRepository.class);
        when(repository.findAll()).thenReturn(List.of());

        assertThrows(ApiException.class, () -> new KanjiStrokeIndex(repository, fixtureLoader()).refs());
    }

    @Test
    void aMostlyMissingIndexIsRejectedButAFewMissingFilesAreTolerated() {
        KanjiRepository repository = mock(KanjiRepository.class);
        String ok = "/assets/kanjivg/kanji/065e5.svg", bad = "/assets/kanjivg/kanji/missing.svg";
        when(repository.findAll()).thenReturn(List.of(kanji("日", ok), kanji("A", bad), kanji("B", bad)));
        assertThrows(ApiException.class, () -> new KanjiStrokeIndex(repository, fixtureLoader()).refs());

        when(repository.findAll()).thenReturn(List.of(kanji("日", ok), kanji("作", "/assets/kanjivg/kanji/04f5c.svg"),
                kanji("B", bad)));
        assertEquals(2, new KanjiStrokeIndex(repository, fixtureLoader()).refs().size());
    }

    @Test
    void worksWithEitherUriPrefixTheImporterCanProduce() {
        // Mặc định của import_data.py là /kanjivg/kanji; lệnh trong docs dùng /assets/kanjivg/kanji.
        KanjiRepository repository = mock(KanjiRepository.class);
        when(repository.findAll()).thenReturn(List.of(
                kanji("日", "/kanjivg/kanji/065e5.svg"),
                kanji("作", "/assets/kanjivg/kanji/04f5c.svg")));

        List<StrokeMatcher.Ref> refs = new KanjiStrokeIndex(repository, fixtureLoader()).refs();

        assertEquals(List.of("日", "作"), refs.stream().map(StrokeMatcher.Ref::character).toList());
    }

    @Test
    void alwaysReadsFromTheFixedKanjiVgFolder() {
        KanjiRepository repository = mock(KanjiRepository.class);
        ResourceLoader loader = fixtureLoader();
        when(repository.findAll()).thenReturn(List.of(kanji("日", "/kanjivg/kanji/065e5.svg")));

        new KanjiStrokeIndex(repository, loader).refs();

        verify(loader).getResource("classpath:/static/assets/kanjivg/kanji/065e5.svg");
    }

    @Test
    void refusesUrisOutsideTheKanjiVgAssetFolder() {
        KanjiRepository repository = mock(KanjiRepository.class);
        when(repository.findAll()).thenReturn(List.of(
                kanji("日", "/assets/kanjivg/kanji/065e5.svg"),
                kanji("作", "/assets/kanjivg/kanji/04f5c.svg"),
                kanji("X", "/assets/kanjivg/../../application-local.yaml"),
                kanji("Y", "file:///etc/passwd")));

        List<StrokeMatcher.Ref> refs = new KanjiStrokeIndex(repository, fixtureLoader()).refs();

        assertEquals(List.of("日", "作"), refs.stream().map(StrokeMatcher.Ref::character).toList());
    }
}
