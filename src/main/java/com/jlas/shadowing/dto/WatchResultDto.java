package com.jlas.shadowing.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class WatchResultDto {
    private boolean countedAsView;
    private int viewCount;
}
