package com.jlas.learning.dto;
import java.util.List;
public record InteractiveCompositionResult(boolean published, int packageCount, List<CoverageGap> gaps) {
    public InteractiveCompositionResult { gaps = List.copyOf(gaps); }
}
