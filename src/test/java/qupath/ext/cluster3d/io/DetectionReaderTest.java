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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import qupath.ext.cluster3d.model.CellRef;
import qupath.ext.cluster3d.model.PointCloudData;

class DetectionReaderTest {

    private static Map<String, Double> map(Object... kv) {
        Map<String, Double> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], (Double) kv[i + 1]);
        }
        return m;
    }

    @Test
    void unionKeepsNamesWithAnyFiniteValue() {
        List<Map<String, Double>> cells = List.of(
                map("A", 1.0, "B", Double.NaN), map("A", 2.0, "C", 5.0, "B", 3.0), map("D", Double.POSITIVE_INFINITY));
        List<String> union = DetectionReader.numericMeasurementUnion(cells);
        assertThat(union).containsExactlyInAnyOrder("A", "B", "C");
    }

    @Test
    void unionExcludesAllNanColumn() {
        List<Map<String, Double>> cells = List.of(map("X", Double.NaN), map("X", Double.NaN, "Y", 1.0));
        assertThat(DetectionReader.numericMeasurementUnion(cells)).containsExactly("Y");
    }

    @Test
    void unionHandlesEmptyAndNull() {
        assertThat(DetectionReader.numericMeasurementUnion(null)).isEmpty();
        assertThat(DetectionReader.numericMeasurementUnion(List.of())).isEmpty();
    }

    @Test
    void sharedScalePreservesAxisProportions() {
        // X spans 0..10, Y and Z span 0..2. A single global scale must keep Y and Z
        // at 1/5 the on-screen extent of X (no per-axis stretch).
        double[] x = new double[11];
        double[] y = new double[11];
        double[] z = new double[11];
        for (int i = 0; i <= 10; i++) {
            x[i] = i;
            y[i] = i * 0.2;
            z[i] = i * 0.2;
        }
        float[][] n = DetectionReader.normalizeShared(x, y, z);
        assertThat((double) n[0][10]).isCloseTo(1.0, within(1e-5));
        assertThat((double) n[0][0]).isCloseTo(-1.0, within(1e-5));
        assertThat((double) n[1][10]).isCloseTo(0.2, within(1e-5));
        assertThat((double) n[1][0]).isCloseTo(-0.2, within(1e-5));
    }

    @Test
    void normalizationIsOutlierRobust() {
        // 0..99 plus one huge outlier. Robust percentile range must NOT be dominated
        // by the outlier: mid-range points keep meaningful spread, outlier is clamped.
        double[] x = new double[101];
        for (int i = 0; i < 100; i++) {
            x[i] = i;
        }
        x[100] = 100000.0;
        double[] zeros = new double[101];
        float[][] n = DetectionReader.normalizeShared(x, zeros, zeros);
        // The outlier is clamped to the axis edge (<= 1), not blown past it.
        assertThat((double) n[0][100]).isLessThanOrEqualTo(1.0 + 1e-6);
        assertThat((double) n[0][100]).isGreaterThan(0.9);
        // A quarter-way point retains real spread (would be ~0 under naive min-max).
        assertThat((double) n[0][25]).isLessThan(-0.4);
    }

    @Test
    void flatAxesNormalizeToZeros() {
        double[] a = {3, 3, 3};
        float[][] n = DetectionReader.normalizeShared(a, a, a);
        assertThat(n[0]).containsExactly(0f, 0f, 0f);
    }

    @Test
    void buildPointCloudRetainsRawValuesAndCountsOmitted() {
        List<DetectionReader.CellRecord> records = new ArrayList<>();
        CellRef ref = new CellRef("img", "image.tiff", 0, 0, 5);
        records.add(new DetectionReader.CellRecord(ref, null, map("X", 0.0, "Y", 0.0, "Z", 0.0)));
        records.add(new DetectionReader.CellRecord(ref, null, map("X", 10.0, "Y", 2.0, "Z", -4.0)));
        // Omitted: missing a finite Z.
        records.add(new DetectionReader.CellRecord(ref, null, map("X", 5.0, "Y", 1.0, "Z", Double.NaN)));

        PointCloudData d = DetectionReader.buildPointCloud(records, "X", "Y", "Z");
        assertThat(d.size()).isEqualTo(2);
        assertThat(d.omittedCount).isEqualTo(1);
        assertThat(d.totalConsidered()).isEqualTo(3);
        // Raw values are the real measurement values, not the normalized coords.
        assertThat(d.rawX).containsExactly(0.0, 10.0);
        assertThat(d.rawY).containsExactly(0.0, 2.0);
        assertThat(d.rawZ).containsExactly(0.0, -4.0);
        // Normalized render coords: X is the widest axis -> +/-1.
        assertThat((double) d.ax[0]).isCloseTo(-1.0, within(1e-5));
        assertThat((double) d.ax[1]).isCloseTo(1.0, within(1e-5));
        // All null class -> single unclassified bucket with both points.
        assertThat(d.classCount()).isEqualTo(1);
        assertThat(d.classCounts[0]).isEqualTo(2);
        assertThat(d.classDisplayName(0)).isEqualTo("Unclassified");
        assertThat(d.xName).isEqualTo("X");
    }

    @Test
    void buildPointCloud2DIgnoresZAndOnlyOmitsOnXorY() {
        List<DetectionReader.CellRecord> records = new ArrayList<>();
        CellRef ref = new CellRef("img", "image.tiff", 0, 0, 5);
        records.add(new DetectionReader.CellRecord(ref, null, map("X", 0.0, "Y", 0.0)));
        records.add(new DetectionReader.CellRecord(ref, null, map("X", 10.0, "Y", 2.0)));
        // A missing/NaN Z must NOT omit the cell in 2D (Z is not used).
        records.add(new DetectionReader.CellRecord(ref, null, map("X", 5.0, "Y", 1.0, "Z", Double.NaN)));
        // Omitted: missing a finite Y.
        records.add(new DetectionReader.CellRecord(ref, null, map("X", 3.0, "Y", Double.NaN)));

        PointCloudData d = DetectionReader.buildPointCloud2D(records, "X", "Y");
        assertThat(d.size()).isEqualTo(3);
        assertThat(d.omittedCount).isEqualTo(1);
        // Z is flat (all zero) so the cloud lies in a plane.
        assertThat(d.rawZ).containsExactly(0.0, 0.0, 0.0);
        for (float z : d.az) {
            assertThat((double) z).isEqualTo(0.0);
        }
        assertThat(d.xName).isEqualTo("X");
        assertThat(d.zName).isEqualTo("");
    }

    // --- farthest-point sampling ---

    @Test
    void farthestPointSampleCollinearPicksMedoidThenExtremes() {
        // Collinear 0..4; centroid = 2 -> medoid index 2, then the two extremes.
        float[] ax = {0, 1, 2, 3, 4};
        float[] zero = {0, 0, 0, 0, 0};
        int[] members = {0, 1, 2, 3, 4};
        assertThat(DetectionReader.farthestPointSample(ax, zero, zero, members, 3))
                .containsExactly(2, 0, 4);
    }

    @Test
    void farthestPointSampleReturnsAllWhenKExceedsMembers() {
        float[] ax = {0, 1, 2, 3, 4};
        float[] zero = {0, 0, 0, 0, 0};
        int[] members = {0, 1, 2, 3, 4};
        int[] reps = DetectionReader.farthestPointSample(ax, zero, zero, members, 10);
        assertThat(reps).hasSize(5).containsExactlyInAnyOrder(0, 1, 2, 3, 4);
        assertThat(reps[0]).isEqualTo(2); // medoid still first
    }

    @Test
    void farthestPointSampleIsDeterministicAndHonorsMemberIndices() {
        float[] ax = new float[13];
        float[] zero = new float[13];
        ax[10] = 0;
        ax[11] = 5;
        ax[12] = 10;
        int[] members = {10, 11, 12};
        // centroid = 5 -> medoid 11; farthest tie (10 & 12 both 5 away) -> earliest member 10.
        assertThat(DetectionReader.farthestPointSample(ax, zero, zero, members, 2))
                .containsExactly(11, 10);
        assertThat(DetectionReader.farthestPointSample(ax, zero, zero, members, 2))
                .containsExactly(DetectionReader.farthestPointSample(ax, zero, zero, members, 2));
        assertThat(DetectionReader.farthestPointSample(ax, zero, zero, members, 0))
                .isEmpty();
        assertThat(DetectionReader.farthestPointSample(ax, zero, zero, new int[0], 3))
                .isEmpty();
    }

    // --- per-image subsample ---

    private static double[] range(int n) {
        double[] a = new double[n];
        for (int i = 0; i < n; i++) {
            a[i] = i;
        }
        return a;
    }

    @Test
    void subsampleNoLimitOrUnderLimitReturnsIdentity() {
        int[] cls = {0, 0, 1, 1};
        double[] x = range(4);
        double[] y = new double[4];
        assertThat(DetectionReader.subsamplePerImage(4, cls, x, y, 0, 1, 42)).containsExactly(0, 1, 2, 3);
        assertThat(DetectionReader.subsamplePerImage(4, cls, x, y, 10, 1, 42)).containsExactly(0, 1, 2, 3);
    }

    @Test
    void subsampleKeepsEveryClusterAndRespectsLimit() {
        // 3 clusters of 10 cells each (30 total); limit 6 -> at most 6 kept, every cluster present.
        int n = 30;
        int[] cls = new int[n];
        double[] x = new double[n];
        double[] y = new double[n];
        for (int i = 0; i < n; i++) {
            cls[i] = i / 10; // clusters 0,1,2
            x[i] = i;
        }
        int[] kept = DetectionReader.subsamplePerImage(n, cls, x, y, 6, 1, 42);
        assertThat(kept.length).isLessThanOrEqualTo(6);
        java.util.Set<Integer> clustersKept = new java.util.HashSet<>();
        for (int idx : kept) {
            clustersKept.add(cls[idx]);
        }
        assertThat(clustersKept).containsExactlyInAnyOrder(0, 1, 2); // no cluster dropped
    }

    @Test
    void subsampleIsDeterministicGivenSeed() {
        int n = 50;
        int[] cls = new int[n];
        double[] x = new double[n];
        double[] y = new double[n];
        for (int i = 0; i < n; i++) {
            cls[i] = i % 4;
            x[i] = i;
            y[i] = (i * 7) % 13;
        }
        int[] a = DetectionReader.subsamplePerImage(n, cls, x, y, 20, 2, 123);
        int[] b = DetectionReader.subsamplePerImage(n, cls, x, y, 20, 2, 123);
        int[] c = DetectionReader.subsamplePerImage(n, cls, x, y, 20, 2, 999);
        assertThat(a).containsExactly(b);
        assertThat(a).hasSize(20);
        // A different seed generally yields a different fill (not asserting inequality strictly,
        // just that both respect the limit).
        assertThat(c).hasSize(20);
    }
}
