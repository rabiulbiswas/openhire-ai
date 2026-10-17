package com.openhire.analytics.domain;

import java.util.Collection;
import java.util.List;
import java.util.OptionalDouble;

public final class Stats {
    private Stats() {}

    /** Median with the mean of the two middle values for an even count. */
    public static OptionalDouble median(Collection<Double> values) {
        if (values.isEmpty()) return OptionalDouble.empty();
        var sorted = values.stream().sorted().toList();
        int n = sorted.size();
        return OptionalDouble.of(n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2);
    }

    /** Nearest-rank percentile (quantile in 0..1). */
    public static OptionalDouble percentile(Collection<Double> values, double quantile) {
        if (values.isEmpty()) return OptionalDouble.empty();
        List<Double> sorted = values.stream().sorted().toList();
        int rank = (int) Math.ceil(quantile * sorted.size());
        return OptionalDouble.of(sorted.get(Math.max(0, Math.min(sorted.size() - 1, rank - 1))));
    }

    public static double round(double value, int decimals) {
        double scale = Math.pow(10, decimals);
        return Math.round(value * scale) / scale;
    }

    public static double pct(long part, long whole) {
        return whole == 0 ? 0 : round(100.0 * part / whole, 1);
    }
}
