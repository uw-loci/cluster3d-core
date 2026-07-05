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

package qupath.ext.cluster3d.model;

import java.util.List;
import javafx.scene.paint.Color;
import qupath.lib.objects.classes.PathClass;

/**
 * Immutable read result: one point per detection, with both the RAW measurement
 * values (for the tooltip / caption, so the numbers the user sees match the real
 * measurement names) and the geometry-preserving normalized render coordinates
 * (used only for drawing). Plus a class index into a shared class list, a color
 * palette, per-class counts, index-aligned {@link CellRef} back-references, and
 * the count of cells omitted because they lacked a finite value on some axis.
 *
 * <p>The normalized arrays {@code ax}/{@code ay}/{@code az} are internal to
 * rendering. The raw arrays {@code rawX}/{@code rawY}/{@code rawZ} hold the actual
 * measurement values in the axis's own units and are what the UI reports.</p>
 */
public final class PointCloudData {

    /** Per-axis normalized render coordinates (index-aligned; internal to drawing). */
    public final float[] ax;

    public final float[] ay;
    public final float[] az;

    /** Raw measurement values in the axis's own units (index-aligned). */
    public final double[] rawX;

    public final double[] rawY;
    public final double[] rawZ;

    /** Per-point class index into {@link #classes}. */
    public final int[] classIdx;

    /** Distinct classes in encounter order; a {@code null} entry means unclassified. */
    public final List<PathClass> classes;

    /** Display color per class, index-aligned with {@link #classes}. */
    public final Color[] palette;

    /** Number of points per class, index-aligned with {@link #classes}. */
    public final int[] classCounts;

    /** Per-point back-reference to the source cell (index-aligned). */
    public final CellRef[] refs;

    /** Measurement names actually plotted on X / Y / Z (for labels + captions). */
    public final String xName;

    public final String yName;
    public final String zName;

    /** Cells dropped because they lacked a finite value on one of the chosen axes. */
    public final int omittedCount;

    public PointCloudData(
            float[] ax,
            float[] ay,
            float[] az,
            double[] rawX,
            double[] rawY,
            double[] rawZ,
            int[] classIdx,
            List<PathClass> classes,
            Color[] palette,
            int[] classCounts,
            CellRef[] refs,
            String xName,
            String yName,
            String zName,
            int omittedCount) {
        this.ax = ax;
        this.ay = ay;
        this.az = az;
        this.rawX = rawX;
        this.rawY = rawY;
        this.rawZ = rawZ;
        this.classIdx = classIdx;
        this.classes = classes;
        this.palette = palette;
        this.classCounts = classCounts;
        this.refs = refs;
        this.xName = xName;
        this.yName = yName;
        this.zName = zName;
        this.omittedCount = omittedCount;
    }

    /** Number of plotted points (survivors -- excludes omitted cells). */
    public int size() {
        return ax == null ? 0 : ax.length;
    }

    /** Total cells considered: plotted survivors plus omitted. */
    public int totalConsidered() {
        return size() + omittedCount;
    }

    /** Number of distinct classes (including the unclassified bucket if present). */
    public int classCount() {
        return classes == null ? 0 : classes.size();
    }

    /** Human-readable name for a class index ({@code "Unclassified"} for a null class). */
    public String classDisplayName(int classIndex) {
        if (classes == null || classIndex < 0 || classIndex >= classes.size()) {
            return "Unclassified";
        }
        PathClass pc = classes.get(classIndex);
        return pc == null ? "Unclassified" : pc.toString();
    }
}
