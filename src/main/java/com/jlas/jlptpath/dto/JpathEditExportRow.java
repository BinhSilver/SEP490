package com.jlas.jlptpath.dto;

/**
 * [JLAS-W0108] Last value of one edited column, written back to the seed source ({@code value} null = NULL). Numeric
 * columns (answer_index) are numbers, every other value a string (JSON columns as compact JSON text).
 */
public record JpathEditExportRow(String targetType, String targetCode, String field, Object value) {
}
