package com.jlas.shadowing.dto;

import lombok.Data;
import java.util.List;

@Data
public class VideoUpdateRequest {
    private String title;
    private List<String> tags;
    /** Format (Anime, News…): exactly one per video. */
    private String category;
    /** Genre codes (ACTION, SCHOOL…); null keeps the current genres, an empty list clears them. */
    private List<String> genres;
    /** PUBLIC | PRIVATE */
    private String visibility;
    /** Age rating: 13, 16 or 18; 0 clears it (all ages); null keeps the current rating. */
    private Integer minAge;
}
