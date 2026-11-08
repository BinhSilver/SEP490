package com.jlas.jlptpath.service;

/** [JLAS-W0108] Whether the topic-based JLPT path is switched on ({@code learning.jlpt-path.enabled}). */
@FunctionalInterface
public interface JpathFeature {

    boolean enabled();
}
