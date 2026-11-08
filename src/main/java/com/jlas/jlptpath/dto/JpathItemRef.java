package com.jlas.jlptpath.dto;

import com.jlas.jlptpath.enums.JpathItemType;

/** [JLAS-W0108] A learnable item by type and code. */
public record JpathItemRef(JpathItemType type, String code) {
}
