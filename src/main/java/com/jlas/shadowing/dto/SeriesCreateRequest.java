package com.jlas.shadowing.dto;

import lombok.Data;
import java.util.List;

@Data
public class SeriesCreateRequest {
    private String title;
    private String description;
    /** N5..N1 or other creator-defined label. */
    private String estimatedLevel;
    /** CREATOR or AI. The current MVP supports CREATOR input. */
    private String levelSource;
    /** PUBLIC or PRIVATE. */
    private String visibility;
    private List<Long> videoIds;
}
