package com.jlas.shadowing.model;

/** Published when a shadowing view is counted; creator milestone rewards run after it commits. */
public record ShadowingViewCounted(Long videoId, Long ownerId, String title, int viewCount) {}
