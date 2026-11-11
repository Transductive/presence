package io.transductive.presence.benchmarks;

final class LargeAccuracyStatistics {
    static final double MARGIN = 0.0005;
    private static final double Z_95 = 1.959963984540054;
    private static final double Z_97_5 = 2.241402727604947;

    record Interval(double lower, double upper) { }
    record Difference(double estimate, double lower, double upper, boolean equivalent) { }

    private LargeAccuracyStatistics() { }

    static Interval wilson(long positives, long trials) {
        return wilson(positives, trials, Z_95);
    }

    private static Interval wilson(long positives, long trials, double z) {
        if (trials <= 0 || positives < 0 || positives > trials) {
            throw new IllegalArgumentException("Invalid binomial counts");
        }
        double p = (double) positives / trials;
        double square = z * z;
        double denominator = 1 + square / trials;
        double center = (p + square / (2 * trials)) / denominator;
        double radius = z * Math.sqrt(p * (1 - p) / trials + square / (4 * trials * (double) trials)) / denominator;
        return new Interval(Math.max(0, center - radius), Math.min(1, center + radius));
    }

    static Difference difference(long standard, long blocked, long trials) {
        // Two 97.5% marginal intervals yield a conservative approximately95% joint
        // interval by Bonferroni, without assuming independent paired outcomes.
        var first = wilson(standard, trials, Z_97_5);
        var second = wilson(blocked, trials, Z_97_5);
        double lower = second.lower() - first.upper();
        double upper = second.upper() - first.lower();
        return new Difference((double) (blocked - standard) / trials, lower, upper,
                lower >= -MARGIN && upper <= MARGIN);
    }
}
