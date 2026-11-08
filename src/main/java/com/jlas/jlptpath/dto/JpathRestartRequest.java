package com.jlas.jlptpath.dto;

/** [JLAS-W0108] Body of {@code POST /path/restart}: the text the learner typed, must read "BẮT ĐẦU LẠI". */
public record JpathRestartRequest(String confirmText) {
}
