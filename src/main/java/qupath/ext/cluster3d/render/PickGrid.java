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

package qupath.ext.cluster3d.render;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure uniform screen-space bucket grid for O(1)-ish point picking. Points are
 * binned into square buckets sized near the pick radius; a pick scans only the
 * pointer's bucket and its neighbors instead of every point.
 *
 * <p>{@link #pick} returns the nearest point index within the radius, breaking
 * distance ties toward the front-most point (smallest depth). No JavaFX
 * dependency, so it is fully unit-testable.</p>
 */
public final class PickGrid {

    private final double cellSize;
    private final Map<Long, List<Entry>> buckets = new HashMap<>();

    private static final class Entry {
        final int index;
        final double sx;
        final double sy;
        final double depth;

        Entry(int index, double sx, double sy, double depth) {
            this.index = index;
            this.sx = sx;
            this.sy = sy;
            this.depth = depth;
        }
    }

    /**
     * @param cellSize bucket edge length in screen px; use roughly the pick radius
     */
    public PickGrid(double cellSize) {
        this.cellSize = cellSize > 0 ? cellSize : 1.0;
    }

    private long key(int cx, int cy) {
        // Pack two ints into a long bucket key.
        return (((long) cx) << 32) ^ (cy & 0xffffffffL);
    }

    private int cellOf(double v) {
        return (int) Math.floor(v / cellSize);
    }

    /** Add a projected point at screen ({@code sx},{@code sy}) with a depth value. */
    public void add(int index, double sx, double sy, double depth) {
        long k = key(cellOf(sx), cellOf(sy));
        buckets.computeIfAbsent(k, kk -> new ArrayList<>()).add(new Entry(index, sx, sy, depth));
    }

    /**
     * Nearest point index to ({@code px},{@code py}) within {@code radius} px, or
     * -1 if none. On a distance tie, the front-most (smallest depth) point wins.
     */
    public int pick(double px, double py, double radius) {
        double r2 = radius * radius;
        int minCx = cellOf(px - radius);
        int maxCx = cellOf(px + radius);
        int minCy = cellOf(py - radius);
        int maxCy = cellOf(py + radius);

        int best = -1;
        double bestD2 = Double.MAX_VALUE;
        double bestDepth = Double.MAX_VALUE;
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cy = minCy; cy <= maxCy; cy++) {
                List<Entry> list = buckets.get(key(cx, cy));
                if (list == null) {
                    continue;
                }
                for (Entry e : list) {
                    double ddx = e.sx - px;
                    double ddy = e.sy - py;
                    double d2 = ddx * ddx + ddy * ddy;
                    if (d2 > r2) {
                        continue;
                    }
                    if (d2 < bestD2 || (d2 == bestD2 && e.depth < bestDepth)) {
                        best = e.index;
                        bestD2 = d2;
                        bestDepth = e.depth;
                    }
                }
            }
        }
        return best;
    }

    /** Number of points added. */
    public int size() {
        int n = 0;
        for (List<Entry> list : buckets.values()) {
            n += list.size();
        }
        return n;
    }
}
