package com.jlas.shadowing.service;

import com.jlas.shadowing.dto.GenreOption;
import com.jlas.shadowing.model.CategoryConstants;
import com.jlas.shadowing.model.ShadowingGenre;
import com.jlas.shadowing.model.Tag;
import com.jlas.shadowing.repository.TagRepository;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * A video's VIDEO tags hold separate groups: one format (Anime, News…), several genres (Hành động, Học đường…)
 * and level/series tags. Changing one group must leave the others as they are.
 */
@Component
public class ShadowingVideoTags {
    private final TagRepository tags;

    public ShadowingVideoTags(TagRepository tags) {
        this.tags = tags;
    }

    /** Tags for the given genre codes, created on first use. Unknown codes or too many genres are rejected. */
    public List<Tag> genreTags(Collection<String> codes) {
        if (codes == null) return List.of();
        Set<ShadowingGenre> genres = new LinkedHashSet<>();
        for (String code : codes) {
            if (code == null || code.isBlank()) continue;
            genres.add(ShadowingGenre.fromCode(code)
                    .orElseThrow(() -> new IllegalArgumentException("Thể loại không hợp lệ: " + code.strip())));
        }
        if (genres.size() > ShadowingGenre.MAX_PER_VIDEO)
            throw new IllegalArgumentException("Mỗi video chọn tối đa " + ShadowingGenre.MAX_PER_VIDEO + " thể loại.");
        return genres.stream().map(this::tag).toList();
    }

    private Tag tag(ShadowingGenre genre) {
        return tags.findByCode(genre.tagCode()).orElseGet(() -> {
            Tag tag = new Tag();
            tag.setCode(genre.tagCode());
            tag.setName(genre.label());
            tag.setTagType(Tag.TagType.VIDEO);
            return tags.save(tag);
        });
    }

    /** Replaces only the genres. */
    public static List<Tag> withGenres(Collection<Tag> current, List<Tag> genres) {
        List<Tag> result = new ArrayList<>(current.stream().filter(tag -> !ShadowingGenre.isGenreTag(tag)).toList());
        result.addAll(genres);
        return result;
    }

    /** Replaces only the format, keeping genres, level and series tags. */
    public static List<Tag> withFormat(Collection<Tag> current, List<Tag> format) {
        List<Tag> result = new ArrayList<>(current.stream()
                .filter(tag -> ShadowingGenre.isGenreTag(tag) || !CategoryConstants.isValid(tag.getName())).toList());
        result.addAll(0, format);
        return result;
    }

    /** The video's genres in the fixed list order. */
    public static List<GenreOption> genresOf(Collection<Tag> current) {
        return current.stream().filter(ShadowingGenre::isGenreTag)
                .map(tag -> ShadowingGenre.fromCode(tag.getCode()).orElse(null))
                .filter(java.util.Objects::nonNull).distinct()
                .sorted(Comparator.naturalOrder()).map(GenreOption::of).toList();
    }
}
