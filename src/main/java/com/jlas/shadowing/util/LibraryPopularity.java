package com.jlas.shadowing.util;

import java.util.Collection;

/** Popularity policy: fixed Bayesian prior, logarithmic P95 caps, and seven-day valid views. */
public final class LibraryPopularity {
    private LibraryPopularity() {}

    public record Metrics(double averageRating, long ratingCount, long views, long recentViews) {}
    public record Caps(double views, double recentViews) {}

    public static Caps caps(Collection<Metrics> library) {
        return new Caps(p95(library.stream().mapToDouble(Metrics::views).toArray()),
                p95(library.stream().mapToDouble(Metrics::recentViews).toArray()));
    }

    public static double score(Metrics video, Caps caps) {
        double count = Math.max(0, video.ratingCount());
        double average = Double.isFinite(video.averageRating())
                ? Math.clamp(video.averageRating(), 0, 5) : 0;
        double rating = (count * average + 20 * 4.0) / (count + 20) / 5;
        return 0.65 * rating + 0.25 * normalize(video.views(), caps.views())
                + 0.10 * normalize(video.recentViews(), caps.recentViews());
    }

    private static double normalize(long value, double cap) {
        return Math.min(1, Math.log1p(Math.max(0, value)) / Math.log1p(Math.max(1, cap)));
    }

    /** Linear interpolation at (n - 1) * .95; empty and zero-only libraries have a safe cap. */
    public static double p95(double[] values) {
        if (values.length == 0) return 1;
        java.util.Arrays.sort(values);
        double index = (values.length - 1) * .95;
        int lower = (int) index;
        int upper = Math.min(lower + 1, values.length - 1);
        return Math.max(1, values[lower] + (values[upper] - values[lower]) * (index - lower));
    }
}
