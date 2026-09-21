package com.jlas.shadowing.dto;

import lombok.Data;
import java.util.List;

@Data
public class VideoUpdateRequest {
    private String title;
    private List<String> tags;
    private String category;
    /** PUBLIC | PRIVATE */
    private String visibility;
}
