package com.jlas.jlptpath.dto;

/** [JLAS-W0108] Last value of one edited column, written back to the seed source ({@code value} null = NULL). */
public record JpathEditExportRow(String targetType, String targetCode, String field, String value) {
}
