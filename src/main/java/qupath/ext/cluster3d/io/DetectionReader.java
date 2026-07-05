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

    /** Result of a read: per-cell records, numeric name union, and a read-error flag. */
    public static final class ReadResult {
        public final List<CellRecord> records;
        public final List<String> numericAxes;
        public final boolean readError;

        public ReadResult(List<CellRecord> records, List<String> numericAxes, boolean readError) {
            this.records = records;
            this.numericAxes = numericAxes;
            this.readError = readError;
        }

        public int size() {
            return records.size();
        }
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
    public static ReadResult readImage(ImageData<BufferedImage> imageData, String imageId, String imageName) {
        List<CellRecord> records = new ArrayList<>();
        List<Map<String, Double>> maps = new ArrayList<>();
        boolean error = false;
        if (imageData != null) {
            try {
                collect(imageData, imageId, imageName, records, maps);
            } catch (Exception e) {
                logger.error("Failed to read detections for image '{}'", imageName, e);
                error = true;
            }
        }
        return new ReadResult(records, numericMeasurementUnion(maps), error);
    }

    /**
     * Read all detections across every image in the project (off the FX thread).
     *
     * @param progress optional callback "image k of N" (may be null)
     */
    public static ReadResult readProject(Project<BufferedImage> project, Consumer<String> progress) {
        return readEntries(project == null ? null : project.getImageList(), progress);
    }

    /**
     * Read all detections across a chosen SUBSET of project images (off the FX
     * thread). The window uses this to honor a user-selected image subset instead
     * of the whole project.
     *
     * @param entries  the selected project-image entries (may be null/empty)
     * @param progress optional callback "image k of N" (may be null)
     */
    public static ReadResult readEntries(List<ProjectImageEntry<BufferedImage>> entries, Consumer<String> progress) {
        List<CellRecord> records = new ArrayList<>();
        List<Map<String, Double>> maps = new ArrayList<>();
        boolean error = false;
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
                    collect(data, entry.getID(), entry.getImageName(), records, maps);
                } catch (Exception e) {
                    logger.error("Could not read image data for '{}'", entry.getImageName(), e);
                    error = true;
                }
            }
        }
        return new ReadResult(records, numericMeasurementUnion(maps), error);
    }

    private static void collect(
            ImageData<BufferedImage> data,
            String imageId,
            String imageName,
            List<CellRecord> records,
            List<Map<String, Double>> maps) {
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
            CellRef ref = new CellRef(imageId, imageName, roi.getCentroidX(), roi.getCentroidY(), half);
            records.add(new CellRecord(ref, det.getPathClass(), m));
            maps.add(m);
        }
    }

    /**
     * Build a plottable cloud from records for the given axis triple. Raw axis
     * values are retained per point; render coordinates use a geometry-preserving
     * shared-scale normalization (see {@link #normalizeShared}). Cells missing a
     * finite value on any chosen axis are skipped and counted in
     * {@link PointCloudData#omittedCount}. No disk access.
     */
    public static PointCloudData buildPointCloud(List<CellRecord> records, String xName, String yName, String zName) {
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
            Double vz = rec.measurements.get(zName);
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
                zName,
                omitted);
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
