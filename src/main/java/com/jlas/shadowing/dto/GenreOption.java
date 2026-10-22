package com.jlas.shadowing.dto;

import com.jlas.shadowing.model.ShadowingGenre;

/** A genre as shown to users: stable code for filters and saving, Vietnamese label for display. */
public record GenreOption(String code, String label) {
    public static GenreOption of(ShadowingGenre genre) {
        return new GenreOption(genre.name(), genre.label());
    }
}
