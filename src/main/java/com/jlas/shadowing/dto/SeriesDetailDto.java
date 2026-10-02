package com.jlas.shadowing.dto;

import lombok.Data;
import java.util.List;

@Data
public class SeriesDetailDto {
    private SeriesSummaryDto series;
    private List<VideoSummaryDto> videos;
}
