package com.jlas.shadowing.dto;

import lombok.Data;

@Data
public class SplitSubtitleRequest {
    private Double splitAtTime;
    private String text1;
    private String text2;
}
