package com.jlas.shadowing.dto;

import lombok.Data;

@Data
public class SttResult {
    private String transcript = "";
    private double confidence;
    private boolean reliable;
    private String failureReason;
}
