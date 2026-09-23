package qupath.ext.cluster3d.io;

import java.util.List;

/**
 * Decides which measurements to plot on the view's axes, in priority order.
 * <p>
 * Pure precedence logic, separated from the pane so it can be tested without a
 * JavaFX toolkit. Detection itself stays in {@link AxisAutoDetect}; the detected
 * triple (or pair) is passed in.
 */
public final class AxisChoice {

    private AxisChoice() {}

    /** Where the chosen axes came from, for the UI's "auto-detected" tag. */
    public enum Source {
        /** The user picked these axes in this session. */
        REQUESTED,
        /** The embedding the host (e.g. a clustering result) says it just wrote. */
        HOST,
        /** The user's remembered per-project choice. */
        REMEMBERED,
        /** Recognised by name as a known embedding family. */
        AUTO_DETECTED,
        /** Nothing matched -- the first measurements, which are rarely meaningful. */
        FALLBACK
    }

    /** The chosen axes and where they came from. */
    public record Result(String[] axes, Source source) {}

    /**
     * Choose {@code n} axes, preferring in order: the user's in-session pick, the host's
     * embedding, the remembered per-project pick, an auto-detected family, then the first
     * {@code n} measurements.
     * <p>
     * HOST outranks REMEMBERED deliberately. A host supplies axes only when it knows which
     * embedding this view is for -- a result window showing one run -- and that run's own
     * embedding is a better default than a choice remembered from some earlier run. An
     * in-session pick still wins over both.
     *
     * @param n            axis count, 2 or 3
     * @param requested    the user's in-session pick, or null
     * @param hostPreferred the host's embedding columns, or null when not host-driven
     * @param remembered   the remembered per-project pick, or null
     * @param autoDetected names {@link AxisAutoDetect} recognised, or null/empty
     * @param numeric      every plottable measurement name; must hold at least {@code n}
     * @return the chosen axes (length {@code n}) and their source
     */
    public static Result choose(
            int n,
            String[] requested,
            String[] hostPreferred,
            String[] remembered,
            List<String> autoDetected,
            List<String> numeric) {
        if (numeric == null || numeric.size() < n) {
            throw new IllegalArgumentException("Need at least " + n + " numeric measurements");
        }
        if (isValid(requested, n, numeric)) {
            return new Result(take(requested, n), Source.REQUESTED);
        }
        if (isValid(hostPreferred, n, numeric)) {
            return new Result(take(hostPreferred, n), Source.HOST);
        }
        if (isValid(remembered, n, numeric)) {
            return new Result(take(remembered, n), Source.REMEMBERED);
        }
        if (autoDetected != null && autoDetected.size() >= n) {
            String[] detected = autoDetected.toArray(new String[0]);
            if (isValid(detected, n, numeric)) {
                return new Result(take(detected, n), Source.AUTO_DETECTED);
            }
        }
        return new Result(take(numeric.toArray(new String[0]), n), Source.FALLBACK);
    }

    /**
     * True when {@code axes} supplies {@code n} distinct names that all exist in
     * {@code numeric}. Distinctness matters: plotting one measurement on two axes
     * collapses the cloud to a line, which reads as a broken view rather than a bad pick.
     */
    private static boolean isValid(String[] axes, int n, List<String> numeric) {
        if (axes == null || axes.length < n) {
            return false;
        }
        for (int i = 0; i < n; i++) {
            String a = axes[i];
            if (a == null || a.isBlank() || !numeric.contains(a)) {
                return false;
            }
            for (int j = 0; j < i; j++) {
                if (a.equals(axes[j])) {
                    return false;
                }
            }
        }
        return true;
    }

    private static String[] take(String[] src, int n) {
        String[] out = new String[n];
        System.arraycopy(src, 0, out, 0, n);
        return out;
    }
}
