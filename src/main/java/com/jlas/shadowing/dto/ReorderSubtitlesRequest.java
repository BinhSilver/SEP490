package com.jlas.shadowing.dto;

import lombok.Data;
import java.util.List;

@Data
public class ReorderSubtitlesRequest {
    private List<Long> order;
}
