package org.example;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Einfache Statistik für die Kalkulation: Median, Quartile und
 * lineare Regression mit Korrelationskoeffizient (Pearson r).
 */
public final class KpiStatistics {

    private KpiStatistics() {
    }

    /** Lageparameter einer Kennzahl über alle Projekte. */
    public record Summary(
            int n,
            Double median,
            Double q1,
            Double q3,
            Double min,
            Double max
    ) {
        public boolean isEmpty() {
            return n == 0;
        }
    }

    /** Ergebnis der Regression y = intercept + slope * x. */
    public record Regression(
            int n,
            double slope,
            double intercept,
            double r
    ) {
        public double predict(double x) {
            return intercept + slope * x;
        }
    }

    /** Fehlende Werte (null) werden ignoriert. */
    public static Summary summarize(List<Double> values) {
        List<Double> sorted = values.stream()
                .filter(Objects::nonNull)
                .filter(value -> !value.isNaN())
                .sorted()
                .toList();

        if (sorted.isEmpty()) {
            return new Summary(0, null, null, null, null, null);
        }

        return new Summary(
                sorted.size(),
                quantile(sorted, 0.50),
                quantile(sorted, 0.25),
                quantile(sorted, 0.75),
                sorted.get(0),
                sorted.get(sorted.size() - 1)
        );
    }

    /**
     * Quantil mit linearer Interpolation (entspricht Excel QUANTIL.INKL).
     * Erwartet eine aufsteigend sortierte Liste ohne null-Werte.
     */
    static double quantile(List<Double> sorted, double p) {
        if (sorted.size() == 1) {
            return sorted.get(0);
        }

        double position = p * (sorted.size() - 1);
        int lower = (int) Math.floor(position);
        int upper = (int) Math.ceil(position);
        double fraction = position - lower;

        return sorted.get(lower)
                + fraction * (sorted.get(upper) - sorted.get(lower));
    }

    /** Anzahl der Projekte, bei denen x und y vorhanden sind. */
    public static int countPairs(List<Double> xs, List<Double> ys) {
        int count = 0;

        for (int i = 0; i < Math.min(xs.size(), ys.size()); i++) {
            if (xs.get(i) != null && ys.get(i) != null) {
                count++;
            }
        }

        return count;
    }

    /**
     * Lineare Regression über alle Paare, bei denen x und y vorhanden sind.
     *
     * @return null, wenn weniger als zwei Paare vorliegen
     *         oder ein Merkmal keine Streuung hat
     */
    public static Regression regression(List<Double> xs, List<Double> ys) {
        List<double[]> pairs = new ArrayList<>();

        for (int i = 0; i < Math.min(xs.size(), ys.size()); i++) {
            Double x = xs.get(i);
            Double y = ys.get(i);

            if (x != null && y != null) {
                pairs.add(new double[]{x, y});
            }
        }

        int n = pairs.size();

        if (n < 2) {
            return null;
        }

        double meanX = pairs.stream().mapToDouble(p -> p[0]).average().orElse(0);
        double meanY = pairs.stream().mapToDouble(p -> p[1]).average().orElse(0);

        double sxy = 0;
        double sxx = 0;
        double syy = 0;

        for (double[] pair : pairs) {
            double dx = pair[0] - meanX;
            double dy = pair[1] - meanY;
            sxy += dx * dy;
            sxx += dx * dx;
            syy += dy * dy;
        }

        if (sxx == 0 || syy == 0) {
            return null;
        }

        double slope = sxy / sxx;
        double intercept = meanY - slope * meanX;
        double r = sxy / Math.sqrt(sxx * syy);

        return new Regression(n, slope, intercept, r);
    }
}
