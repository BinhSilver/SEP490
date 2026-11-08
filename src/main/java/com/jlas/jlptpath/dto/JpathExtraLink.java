package com.jlas.jlptpath.dto;

/** [JLAS-W0108] One link of the "Luyện thêm" strip; {@code url} null and a {@code note} when it is not open. */
public record JpathExtraLink(String key, String label, String url, boolean available, String note) {
}
