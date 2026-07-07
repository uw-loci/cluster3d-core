/*
 * Copyright 2026 Mike Nelson and contributors.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package qupath.ext.cluster3d.io;

import java.awt.Shape;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Consumer;
import javafx.scene.paint.Color;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.cluster3d.model.CellRef;
import qupath.ext.cluster3d.model.PointCloudData;
import qupath.lib.common.ColorTools;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.roi.interfaces.ROI;

/**
 * Reads detections + measurements for the current image or the whole project and
 * builds a {@link PointCloudData} for three chosen axes.
 *
 * <p>The disk-touching reads ({@link #readImage}, {@link #readProject}) MUST be
 * called off the JavaFX application thread. They return a {@link ReadResult}
 * holding one {@link CellRecord} per detection plus the numeric measurement union
 * for the axis pickers. {@link #buildPointCloud} turns records into a plottable
 * cloud for a chosen axis triple. The caller should DROP the {@link ReadResult}
 * (and thus the per-cell measurement maps) once a cloud is built -- the built
 * {@link PointCloudData} keeps only the three raw axis values, class index, and
 * {@link CellRef} per point, so whole measurement maps are not retained for the
 * session.</p>
 *
 * <p>The measurement-union / numeric-filter and the geometry-preserving axis
 * normalization live in pure static methods so they are unit-testable.</p>
 */
public final class DetectionReader {

    private static final Logger logger = LoggerFactory.getLogger(DetectionReader.class);
    private static final Color UNCLASSIFIED_COLOR = Color.gray(0.6);
    // Percentile band used for outlier-robust range estimation.
    private static final double PCT_LOW = 2.0;
    private static final double PCT_HIGH = 98.0;
    // Curve-flattening tolerance (px) for the stored segmentation outline: bounds the
    // vertex count and drops curve segments so each stored outline is small + predictable.
    private static final double OUTLINE_FLATNESS = 0.5;

    private DetectionReader() {}

    /** One detection: its back-reference, class, and numeric measurement map. */
    public static final class CellRecord {
        public final CellRef ref;
        public final PathClass pathClass;
        public final Map<String, Double> measurements;

        public CellRecord(CellRef ref, PathClass pathClass, Map<String, Double> measurements) {
            this.ref = ref;
            this.pathClass = pathClass;
            this.measurements = measurements;
        }
    }

    /** Result of a read: per-cell records, numeric name union, a read-error flag, and
     *  whether any source image was subsampled to honor the per-image cell limit. */
    public static final class ReadResult {
        public final List<CellRecord> records;
        public final List<String> numericAxes;
        public final boolean readError;
        public final boolean limited;

        public ReadResult(List<CellRecord> records, List<String> numericAxes, boolean readError) {
            this(records, numericAxes, readError, false);
        }

        public ReadResult(List<CellRecord> records, List<String> numericAxes, boolean readError, boolean limited) {
            this.records = records;
            this.numericAxes = numericAxes;
            this.readError = readError;
            this.limited = limited;
        }

        public int size() {
            return records.size();
        }
    }

    /**
     * Per-image cell-limit options. When {@link #cellLimit} &gt; 0, each source image is
     * subsampled to at most that many cells: every cluster keeps at least
     * {@link #minPerCluster} farthest-point representatives, the rest of the budget is
     * filled at random using {@link #seed} (deterministic).
     */
    public static final class ReadOptions {
        public final int cellLimit;
        public final int minPerCluster;
        public final long seed;

        public ReadOptions(int cellLimit, int minPerCluster, long seed) {
            this.cellLimit = cellLimit;
            this.minPerCluster = minPerCluster;
            this.seed = seed;
        }

        /** No limit -- read every cell. */
        public static final ReadOptions UNLIMITED = new ReadOptions(0, 1, 0);
    }

    /**
     * Union of measurement names across cells, keeping only names that carry at
     * least one finite (non-NaN, non-infinite) value. Pure and unit-testable.
     */
    public static List<String> numericMeasurementUnion(List<Map<String, Double>> cells) {
        Map<String, Boolean> hasFinite = new LinkedHashMap<>();
        if (cells != null) {
            for (Map<String, Double> cell : cells) {
                if (cell == null) {
                    continue;
                }
                for (Map.Entry<String, Double> e : cell.entrySet()) {
                    String name = e.getKey();
                    if (name == null) {
                        continue;
                    }
                    Double v = e.getValue();
                    boolean finite = v != null && !v.isNaN() && !v.isInfinite();
                    hasFinite.merge(name, finite, (a, b) -> a || b);
                }
            }
        }
        TreeSet<String> out = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (Map.Entry<String, Boolean> e : hasFinite.entrySet()) {
            if (Boolean.TRUE.equals(e.getValue())) {
                out.add(e.getKey());
            }
        }
        return new ArrayList<>(out);
    }

    /** Read all detections of a single image (off the FX thread). */
    public static ReadResult readImage(
            ImageData<BufferedImage> imageData, String imageId, String imageName, ReadOptions opts) {
        List<CellRecord> records = new ArrayList<>();
        List<Map<String, Double>> maps = new ArrayList<>();
        boolean error = false;
        boolean limited = false;
        if (imageData != null) {
            try {
                limited = collectImage(imageData, imageId, imageName, opts, records, maps);
            } catch (Exception e) {
                logger.error("Failed to read detections for image '{}'", imageName, e);
                error = true;
            }
        }
        return new ReadResult(records, numericMeasurementUnion(maps), error, limited);
    }

    /**
     * Read all detections across every image in the project (off the FX thread).
     *
     * @param progress optional callback "image k of N" (may be null)
     */
    public static ReadResult readProject(Project<BufferedImage> project, Consumer<String> progress, ReadOptions opts) {
        return readEntries(project == null ? null : project.getImageList(), progress, opts);
    }

    /**
     * Read all detections across a chosen SUBSET of project images (off the FX
     * thread). The window uses this to honor a user-selected image subset instead
     * of the whole project.
     *
     * @param entries  the selected project-image entries (may be null/empty)
     * @param progress optional callback "image k of N" (may be null)
     * @param opts     per-image cell-limit options
     */
    public static ReadResult readEntries(
            List<ProjectImageEntry<BufferedImage>> entries, Consumer<String> progress, ReadOptions opts) {
        List<CellRecord> records = new ArrayList<>();
        List<Map<String, Double>> maps = new ArrayList<>();
        boolean error = false;
        boolean limited = false;
        if (entries != null) {
            int n = entries.size();
            int k = 0;
            for (ProjectImageEntry<BufferedImage> entry : entries) {
                k++;
                if (progress != null) {
                    progress.accept("Reading image " + k + " of " + n + "...");
                }
                try {
                    ImageData<BufferedImage> data = entry.readImageData();
                    limited |= collectImage(data, entry.getID(), entry.getImageName(), opts, records, maps);
                } catch (Exception e) {
                    logger.error("Could not read image data for '{}'", entry.getImageName(), e);
                    error = true;
                }
            }
        }
        return new ReadResult(records, numericMeasurementUnion(maps), error, limited);
    }

    /**
     * Collect one image's detections into the shared record/measurement lists, subsampling
     * to at most {@code opts.cellLimit} cells per image (0 = no limit). Returns true if this
     * image was subsampled.
     */
    private static boolean collectImage(
            ImageData<BufferedImage> data,
            String imageId,
            String imageName,
            ReadOptions opts,
            List<CellRecord> outRecords,
            List<Map<String, Double>> outMaps) {
        List<CellRecord> recs = new ArrayList<>();
        List<Map<String, Double>> maps = new ArrayList<>();
        List<Double> xs = new ArrayList<>();
        List<Double> ys = new ArrayList<>();
        List<Integer> classIdx = new ArrayList<>();
        Map<PathClass, Integer> classOf = new LinkedHashMap<>(); // per-image cluster index

        for (PathObject det : data.getHierarchy().getDetectionObjects()) {
            ROI roi = det.getROI();
            if (roi == null) {
                continue;
            }
            var ml = det.getMeasurements();
            Map<String, Double> m = new LinkedHashMap<>();
            for (String name : ml.keySet()) {
                Number v = ml.get(name);
                m.put(name, v == null ? Double.NaN : v.doubleValue());
            }
            double half = 0.5 * Math.max(roi.getBoundsWidth(), roi.getBoundsHeight());
            PathClass pc = det.getPathClass();
            PathClass key = (pc == PathClass.getNullClass()) ? null : pc;
            // Precompute the segmentation outline (flattened, full-res px) + packed class color
            // once here on the single read thread -- so the crop service can draw it later
            // without retaining the live ROI or calling its non-thread-safe getShape().
            Shape outline = captureOutline(roi);
            int rgb = (pc != null && pc != PathClass.getNullClass() && pc.getColor() != null) ? pc.getColor() : 0;
            CellRef ref =
                    new CellRef(imageId, imageName, roi.getCentroidX(), roi.getCentroidY(), half, outline, rgb);
            recs.add(new CellRecord(ref, pc, m));
            maps.add(m);
            xs.add(roi.getCentroidX());
            ys.add(roi.getCentroidY());
            classIdx.add(classOf.computeIfAbsent(key, k -> classOf.size()));
        }

        int n = recs.size();
        ReadOptions o = opts == null ? ReadOptions.UNLIMITED : opts;
        if (o.cellLimit <= 0 || n <= o.cellLimit) {
            outRecords.addAll(recs);
            outMaps.addAll(maps);
            return false;
        }
        int[] kept = subsamplePerImage(
                n, toIntArray(classIdx), toDoubleArray(xs), toDoubleArray(ys), o.cellLimit, o.minPerCluster, o.seed);
        for (int idx : kept) {
            outRecords.add(recs.get(idx));
            outMaps.add(maps.get(idx));
        }
        return true;
    }

    private static int[] toIntArray(List<Integer> list) {
        int[] out = new int[list.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = list.get(i);
        }
        return out;
    }

    /**
     * Build a plottable cloud from records for the given axis triple. Raw axis
     * values are retained per point; render coordinates use a geometry-preserving
     * shared-scale normalization (see {@link #normalizeShared}). Cells missing a
     * finite value on any chosen axis are skipped and counted in
     * {@link PointCloudData#omittedCount}. No disk access.
     */
    public static PointCloudData buildPointCloud(List<CellRecord> records, String xName, String yName, String zName) {
        return build(records, xName, yName, zName);
    }

    /**
     * Build a plottable FLAT 2D cloud from records for the given axis PAIR (Z is not used).
     * Cells missing a finite value on X or Y are omitted; Z is set to a constant so the
     * cloud renders flat. Use for a genuine 2D embedding (a 2D UMAP), NOT for two axes of
     * a 3D embedding. No disk access.
     */
    public static PointCloudData buildPointCloud2D(List<CellRecord> records, String xName, String yName) {
        return build(records, xName, yName, null);
    }

    /** Shared build: {@code zName == null} builds a flat 2D cloud (Z ignored, set to 0). */
    private static PointCloudData build(List<CellRecord> records, String xName, String yName, String zName) {
        boolean twoD = zName == null;
        List<Double> xs = new ArrayList<>();
        List<Double> ys = new ArrayList<>();
        List<Double> zs = new ArrayList<>();
        List<CellRef> refs = new ArrayList<>();
        List<Integer> classIdxList = new ArrayList<>();
        List<PathClass> classes = new ArrayList<>();
        Map<PathClassKey, Integer> classIndexOf = new LinkedHashMap<>();
        int omitted = 0;

        for (CellRecord rec : records) {
            Double vx = rec.measurements.get(xName);
            Double vy = rec.measurements.get(yName);
            Double vz = twoD ? Double.valueOf(0.0) : rec.measurements.get(zName);
            if (!finite(vx) || !finite(vy) || !finite(vz)) {
                omitted++;
                continue;
            }
            xs.add(vx);
            ys.add(vy);
            zs.add(vz);
            refs.add(rec.ref);
            PathClassKey key = new PathClassKey(rec.pathClass);
            Integer ci = classIndexOf.get(key);
            if (ci == null) {
                ci = classes.size();
                classIndexOf.put(key, ci);
                classes.add(rec.pathClass);
            }
            classIdxList.add(ci);
        }

        int n = xs.size();
        double[] rawX = toDoubleArray(xs);
        double[] rawY = toDoubleArray(ys);
        double[] rawZ = toDoubleArray(zs);
        float[][] norm = normalizeShared(rawX, rawY, rawZ);

        int[] classIdx = new int[n];
        int[] counts = new int[classes.size()];
        for (int i = 0; i < n; i++) {
            classIdx[i] = classIdxList.get(i);
            counts[classIdx[i]]++;
        }

        Color[] palette = new Color[classes.size()];
        for (int i = 0; i < classes.size(); i++) {
            palette[i] = colorFor(classes.get(i));
        }

        return new PointCloudData(
                norm[0],
                norm[1],
                norm[2],
                rawX,
                rawY,
                rawZ,
                classIdx,
                classes,
                palette,
                counts,
                refs.toArray(new CellRef[0]),
                xName,
                yName,
                twoD ? "" : zName,
                omitted);
    }

    /**
     * Capture a detection's segmentation outline as a compact, flattened {@link Shape} in
     * full-resolution pixel coordinates, or {@code null} if unavailable. Best-effort: point
     * ROIs have no drawable shape ({@code getShape()} throws) and any other failure is
     * swallowed -- a missing outline must never break the read.
     */
    private static Shape captureOutline(ROI roi) {
        if (roi == null || roi.isPoint()) {
            return null;
        }
        try {
            return flattenOutline(roi.getShape());
        } catch (Exception | LinkageError e) {
            return null;
        }
    }

    /**
     * Flatten a shape (removing curve segments) into a {@link Path2D.Float} using
     * {@link #OUTLINE_FLATNESS}. Package-private + pure for unit testing. Returns null for a
     * null input.
     */
    static Shape flattenOutline(Shape shape) {
        if (shape == null) {
            return null;
        }
        Path2D.Float out = new Path2D.Float();
        out.append(shape.getPathIterator(null, OUTLINE_FLATNESS), false);
        return out;
    }

    private static boolean finite(Double v) {
        return v != null && !v.isNaN() && !v.isInfinite();
    }

    private static double[] toDoubleArray(List<Double> list) {
        double[] out = new double[list.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = list.get(i);
        }
        return out;
    }

    /**
     * Geometry-preserving normalization: each axis is centered on its own
     * outlier-robust midpoint (midpoint of the {@code PCT_LOW}..{@code PCT_HIGH}
     * percentile band), then ALL three axes are divided by a SINGLE global scale
     * (the largest per-axis half-range), so the relative proportions of the
     * embedding are preserved (no per-axis stretch). Points outside an axis's
     * percentile band are clamped to that axis's edge for display.
     *
     * @return {@code {ax, ay, az}} normalized render coordinates
     */
    public static float[][] normalizeShared(double[] x, double[] y, double[] z) {
        int n = x.length;
        float[] ax = new float[n];
        float[] ay = new float[n];
        float[] az = new float[n];
        if (n == 0) {
            return new float[][] {ax, ay, az};
        }
        double[] mx = midHalf(x);
        double[] my = midHalf(y);
        double[] mz = midHalf(z);
        double globalScale = Math.max(mx[1], Math.max(my[1], mz[1]));
        if (globalScale <= 0) {
            return new float[][] {ax, ay, az}; // all flat -> zeros
        }
        fillAxis(x, mx[0], mx[1], globalScale, ax);
        fillAxis(y, my[0], my[1], globalScale, ay);
        fillAxis(z, mz[0], mz[1], globalScale, az);
        return new float[][] {ax, ay, az};
    }

    /** Returns {midpoint, half-range} from the robust percentile band of the axis. */
    private static double[] midHalf(double[] a) {
        double[] sorted = a.clone();
        java.util.Arrays.sort(sorted);
        double lo = percentile(sorted, PCT_LOW);
        double hi = percentile(sorted, PCT_HIGH);
        return new double[] {(lo + hi) / 2.0, (hi - lo) / 2.0};
    }

    private static void fillAxis(double[] a, double mid, double half, double globalScale, float[] out) {
        double edge = half / globalScale; // this axis's clamped extent within [-1, 1]
        for (int i = 0; i < a.length; i++) {
            double v = (a[i] - mid) / globalScale;
            if (v > edge) {
                v = edge;
            } else if (v < -edge) {
                v = -edge;
            }
            out[i] = (float) v;
        }
    }

    /** Nearest-rank percentile of a pre-sorted array (p in 0..100). */
    static double percentile(double[] sorted, double p) {
        if (sorted.length == 0) {
            return 0.0;
        }
        if (sorted.length == 1) {
            return sorted[0];
        }
        int idx = (int) Math.round((p / 100.0) * (sorted.length - 1));
        idx = Math.max(0, Math.min(sorted.length - 1, idx));
        return sorted[idx];
    }

    private static Color colorFor(PathClass pc) {
        if (pc == null || pc == PathClass.getNullClass()) {
            return UNCLASSIFIED_COLOR;
        }
        Integer rgb = pc.getColor();
        if (rgb == null) {
            return UNCLASSIFIED_COLOR;
        }
        return Color.rgb(ColorTools.red(rgb), ColorTools.green(rgb), ColorTools.blue(rgb));
    }

    /**
     * Farthest-point sampling (PURE, unit-testable). Returns up to {@code k} ordered cell
     * indices (a subset of {@code memberIndices}) that spread across the members: the first
     * is the MEDOID (member nearest the centroid), then each subsequent pick is the member
     * with the greatest minimum distance to the already-selected set. Deterministic (ties
     * resolve to the earliest member). Returns all members (FPS order) when
     * {@code k >= memberIndices.length}. Used for per-class representatives (embedding space)
     * and the per-image subsample's guaranteed minimum (spatial-centroid space).
     */
    public static int[] farthestPointSample(float[] ax, float[] ay, float[] az, int[] memberIndices, int k) {
        int m = memberIndices == null ? 0 : memberIndices.length;
        int kk = Math.min(Math.max(0, k), m);
        if (kk == 0) {
            return new int[0];
        }
        double cx = 0, cy = 0, cz = 0;
        for (int idx : memberIndices) {
            cx += ax[idx];
            cy += ay[idx];
            cz += az[idx];
        }
        cx /= m;
        cy /= m;
        cz /= m;
        int medoidPos = 0;
        double bestDist = Double.MAX_VALUE;
        for (int p = 0; p < m; p++) {
            int idx = memberIndices[p];
            double d = sq(ax[idx] - cx) + sq(ay[idx] - cy) + sq(az[idx] - cz);
            if (d < bestDist) {
                bestDist = d;
                medoidPos = p;
            }
        }

        boolean[] chosen = new boolean[m];
        double[] minDist = new double[m];
        java.util.Arrays.fill(minDist, Double.MAX_VALUE);
        int[] result = new int[kk];

        int selPos = medoidPos;
        for (int step = 0; step < kk; step++) {
            chosen[selPos] = true;
            result[step] = memberIndices[selPos];
            int selIdx = memberIndices[selPos];
            for (int p = 0; p < m; p++) {
                if (chosen[p]) {
                    continue;
                }
                int idx = memberIndices[p];
                double d = sq(ax[idx] - ax[selIdx]) + sq(ay[idx] - ay[selIdx]) + sq(az[idx] - az[selIdx]);
                if (d < minDist[p]) {
                    minDist[p] = d;
                }
            }
            if (step + 1 < kk) {
                double far = -1;
                int nextPos = -1;
                for (int p = 0; p < m; p++) {
                    if (!chosen[p] && minDist[p] > far) {
                        far = minDist[p];
                        nextPos = p;
                    }
                }
                selPos = nextPos;
            }
        }
        return result;
    }

    private static double sq(double v) {
        return v * v;
    }

    /**
     * Per-image cell subsample (PURE, unit-testable, deterministic given {@code seed}). Picks
     * at most {@code limit} of the {@code n} cells so that:
     * <ol>
     *   <li>every cluster keeps at least {@code min(max(1, minPerCluster), clusterSize)}
     *       farthest-point representatives (spread by spatial centroid {@code (x, y)}), added
     *       round-robin across clusters so every cluster is represented up to the limit;</li>
     *   <li>the remaining budget is filled at random (seeded) from the non-kept cells --
     *       proportional to cluster size, since bigger clusters have more remaining cells.</li>
     * </ol>
     * Returns the kept cell indices in ascending order. When {@code limit <= 0} or
     * {@code n <= limit}, returns the identity {@code 0..n-1} (no subsample).
     *
     * @param classIdx per-cell cluster index (0-based, contiguous within the image)
     * @param x        per-cell spatial centroid X
     * @param y        per-cell spatial centroid Y
     */
    public static int[] subsamplePerImage(
            int n, int[] classIdx, double[] x, double[] y, int limit, int minPerCluster, long seed) {
        if (limit <= 0 || n <= limit) {
            int[] all = new int[n];
            for (int i = 0; i < n; i++) {
                all[i] = i;
            }
            return all;
        }
        int nClusters = 0;
        for (int c : classIdx) {
            nClusters = Math.max(nClusters, c + 1);
        }
        // Members per cluster.
        List<List<Integer>> members = new ArrayList<>();
        for (int c = 0; c < nClusters; c++) {
            members.add(new ArrayList<>());
        }
        for (int i = 0; i < n; i++) {
            members.get(classIdx[i]).add(i);
        }

        // FPS coords (spatial centroid; z = 0). float[] indexed by cell index.
        float[] fx = new float[n];
        float[] fy = new float[n];
        float[] fz = new float[n];
        for (int i = 0; i < n; i++) {
            fx[i] = (float) x[i];
            fy[i] = (float) y[i];
        }

        int minKeep = Math.max(1, minPerCluster);
        boolean[] kept = new boolean[n];
        int keptCount = 0;

        // Phase 1: round-robin the guaranteed reps so every cluster is represented up to limit.
        int[][] repOrder = new int[nClusters][];
        for (int c = 0; c < nClusters; c++) {
            int[] mem = toIntArray(members.get(c));
            repOrder[c] = farthestPointSample(fx, fy, fz, mem, Math.min(minKeep, mem.length));
        }
        for (int round = 0; round < minKeep && keptCount < limit; round++) {
            for (int c = 0; c < nClusters && keptCount < limit; c++) {
                if (round < repOrder[c].length) {
                    int idx = repOrder[c][round];
                    if (!kept[idx]) {
                        kept[idx] = true;
                        keptCount++;
                    }
                }
            }
        }

        // Phase 2: random fill from the remaining cells (seeded, ~proportional to cluster size).
        int budget = limit - keptCount;
        if (budget > 0) {
            List<Integer> remaining = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                if (!kept[i]) {
                    remaining.add(i);
                }
            }
            java.util.Collections.shuffle(remaining, new java.util.Random(seed));
            for (int j = 0; j < budget && j < remaining.size(); j++) {
                kept[remaining.get(j)] = true;
            }
        }

        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (kept[i]) {
                out.add(i);
            }
        }
        return toIntArray(out);
    }

    /** Map key that treats the null / null-class as the single "unclassified" bucket. */
    private static final class PathClassKey {
        private final PathClass pc;

        PathClassKey(PathClass pc) {
            this.pc = (pc == PathClass.getNullClass()) ? null : pc;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof PathClassKey)) {
                return false;
            }
            PathClassKey other = (PathClassKey) o;
            return pc == null ? other.pc == null : pc.equals(other.pc);
        }

        @Override
        public int hashCode() {
            return pc == null ? 0 : pc.hashCode();
        }
    }
}
