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
package qupath.ext.cluster3d.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the pure level-of-detail logic: the footprint gate ({@code planLod}),
 * the viewport-cull predicate ({@code isOnScreenCandidate}), and the screen-space
 * occlusion selection ({@code selectVisibleThumbnails}). Live rendering is WSL-smoke /
 * Windows territory; only this pure math is unit-tested.
 */
class PointCloudViewLodTest {

    private static final double B = 1000; // baseScale that yields a 45px footprint at zoom 1

    // --- footprint gate ---

    @Test
    void inactiveWhenToggleOff() {
        PointCloudView.LodPlan p = PointCloudView.planLod(false, B, 5, 14, 120);
        assertThat(p.thumbnailsActive).isFalse();
    }

    @Test
    void footprintGateInactiveWhenZoomedOut() {
        // footprint = 100 * 1 * 0.045 = 4.5 px < 14 -> still points.
        PointCloudView.LodPlan p = PointCloudView.planLod(true, 100, 1, 14, 120);
        assertThat(p.thumbnailsActive).isFalse();
    }

    @Test
    void activeWhenZoomedIn() {
        // footprint = 1000 * 1 * 0.045 = 45 px >= 14 -> thumbnails on, size 45.
        PointCloudView.LodPlan p = PointCloudView.planLod(true, B, 1, 14, 120);
        assertThat(p.thumbnailsActive).isTrue();
        assertThat(p.thumbSizePx).isCloseTo(45.0, within(1e-9));
    }

    @Test
    void thumbSizeClampedToMax() {
        PointCloudView.LodPlan p = PointCloudView.planLod(true, 100000, 10, 14, 120);
        assertThat(p.thumbSizePx).isEqualTo(120.0);
    }

    // --- viewport culling ---

    @Test
    void onScreenCandidateInsideCanvas() {
        assertThat(PointCloudView.isOnScreenCandidate(400, 300, 800, 600, 120)).isTrue();
        assertThat(PointCloudView.isOnScreenCandidate(0, 0, 800, 600, 120)).isTrue();
    }

    @Test
    void onScreenCandidateWithinMarginIsIncluded() {
        assertThat(PointCloudView.isOnScreenCandidate(-100, -100, 800, 600, 120))
                .isTrue();
        assertThat(PointCloudView.isOnScreenCandidate(900, 700, 800, 600, 120)).isTrue();
    }

    @Test
    void offScreenBeyondMarginIsCulled() {
        assertThat(PointCloudView.isOnScreenCandidate(-500, 300, 800, 600, 120)).isFalse();
        assertThat(PointCloudView.isOnScreenCandidate(2000, 300, 800, 600, 120)).isFalse();
        assertThat(PointCloudView.isOnScreenCandidate(400, -500, 800, 600, 120)).isFalse();
        assertThat(PointCloudView.isOnScreenCandidate(400, 2000, 800, 600, 120)).isFalse();
    }

    // --- screen-space occlusion by overlap (greedy nearest-first NMS, <= 10% overlap) ---
    // drawOrder is passed FAR-to-near (as the real draw loop produces it); the selector
    // iterates it in reverse so the front-most (min-depth) cell is processed first.

    private static final double S = 30; // thumbnail size
    private static final double MAXO = 0.10;

    @Test
    void overlappingThumbnailsKeepOnlyFrontMost() {
        // Centers 15px apart -> overlap 50% > 10%. Index 1 is nearer (depth 2 < 5).
        // drawOrder far-first = {0 (far), 1 (near)}.
        int[] order = {0, 1};
        double[] sx = {100, 115};
        double[] sy = {100, 100};
        double[] depth = {5.0, 2.0};
        Set<Integer> sel = PointCloudView.selectVisibleThumbnails(order, 2, sx, sy, depth, S, 800, 600, 120, MAXO);
        assertThat(sel).containsExactly(1);
    }

    @Test
    void separatedThumbnailsAreBothAccepted() {
        // Centers a full thumbnail-side apart -> 0% overlap -> both kept.
        int[] order = {0, 1};
        double[] sx = {100, 130};
        double[] sy = {100, 100};
        double[] depth = {5.0, 5.0};
        Set<Integer> sel = PointCloudView.selectVisibleThumbnails(order, 2, sx, sy, depth, S, 800, 600, 120, MAXO);
        assertThat(sel).containsExactlyInAnyOrder(0, 1);
    }

    @Test
    void exactlyAtThresholdIsAcceptedJustOverIsSuppressed() {
        // dx = 0.9*s = 27 -> frac = (30-27)/30 = 0.10 exactly -> NOT > 0.10 -> both kept.
        int[] order = {0, 1};
        double[] atBoundaryX = {100, 127};
        double[] sy = {100, 100};
        double[] depth = {5.0, 2.0};
        assertThat(PointCloudView.selectVisibleThumbnails(order, 2, atBoundaryX, sy, depth, S, 800, 600, 120, MAXO))
                .containsExactlyInAnyOrder(0, 1);
        // dx = 26 -> frac = (30-26)/30 = 0.133 > 0.10 -> only the nearer (index 1).
        double[] justOverX = {100, 126};
        assertThat(PointCloudView.selectVisibleThumbnails(order, 2, justOverX, sy, depth, S, 800, 600, 120, MAXO))
                .containsExactly(1);
    }

    @Test
    void denseRowSurvivesAsSpacedSubset() {
        // 5 cells at x = 0,10,20,30,40 (10px apart, each overlaps its neighbor > 10%).
        // Depth ascending with x -> x=0 is nearest. NMS keeps x=0 then x=30 (>= s apart).
        int[] order = {4, 3, 2, 1, 0}; // far-first (x=40 farthest)
        double[] sx = {0, 10, 20, 30, 40};
        double[] sy = {0, 0, 0, 0, 0};
        double[] depth = {0, 10, 20, 30, 40};
        Set<Integer> sel = PointCloudView.selectVisibleThumbnails(order, 5, sx, sy, depth, S, 800, 600, 120, MAXO);
        assertThat(sel).containsExactlyInAnyOrder(0, 3);
    }

    @Test
    void offScreenCellsAreNotSelected() {
        int[] order = {0, 1};
        double[] sx = {-500, 2000};
        double[] sy = {100, 100};
        double[] depth = {5.0, 5.0};
        Set<Integer> sel = PointCloudView.selectVisibleThumbnails(order, 2, sx, sy, depth, S, 800, 600, 120, MAXO);
        assertThat(sel).isEmpty();
    }

    // --- per-cluster representatives (farthest-point sampling) ---

    @Test
    void farthestPointSampleCollinearPicksMedoidThenExtremes() {
        // Collinear 0..4; centroid = 2 -> medoid index 2, then the two extremes.
        float[] ax = {0, 1, 2, 3, 4};
        float[] zero = {0, 0, 0, 0, 0};
        int[] members = {0, 1, 2, 3, 4};
        int[] reps = PointCloudView.farthestPointSample(ax, zero, zero, members, 3);
        assertThat(reps).containsExactly(2, 0, 4);
    }

    @Test
    void farthestPointSampleReturnsAllWhenKExceedsMembers() {
        float[] ax = {0, 1, 2, 3, 4};
        float[] zero = {0, 0, 0, 0, 0};
        int[] members = {0, 1, 2, 3, 4};
        int[] reps = PointCloudView.farthestPointSample(ax, zero, zero, members, 10);
        assertThat(reps).hasSize(5).containsExactlyInAnyOrder(0, 1, 2, 3, 4);
        assertThat(reps[0]).isEqualTo(2); // medoid still first
    }

    @Test
    void farthestPointSampleIsDeterministic() {
        float[] ax = {0, 1, 2, 3, 4};
        float[] zero = {0, 0, 0, 0, 0};
        int[] members = {0, 1, 2, 3, 4};
        int[] a = PointCloudView.farthestPointSample(ax, zero, zero, members, 4);
        int[] b = PointCloudView.farthestPointSample(ax, zero, zero, members, 4);
        assertThat(a).containsExactly(b);
    }

    @Test
    void farthestPointSampleHonorsMemberIndicesAndEdgeCases() {
        // members are a subset with non-contiguous indices.
        float[] ax = new float[13];
        float[] zero = new float[13];
        ax[10] = 0;
        ax[11] = 5;
        ax[12] = 10;
        int[] members = {10, 11, 12};
        // centroid = 5 -> medoid 11; farthest tie (10 & 12 both 5 away) -> earliest member 10.
        assertThat(PointCloudView.farthestPointSample(ax, zero, zero, members, 2))
                .containsExactly(11, 10);
        assertThat(PointCloudView.farthestPointSample(ax, zero, zero, members, 0))
                .isEmpty();
        assertThat(PointCloudView.farthestPointSample(ax, zero, zero, new int[0], 3))
                .isEmpty();
    }

    @Test
    void representativeIsCandidateEvenWhenFootprintGateClosed() {
        // Gate closed (zoomed out): a representative is still a candidate; a non-rep is not.
        assertThat(PointCloudView.isThumbnailCandidate(false, true)).isTrue();
        assertThat(PointCloudView.isThumbnailCandidate(false, false)).isFalse();
        // Gate open (zoomed in): every cell is a candidate.
        assertThat(PointCloudView.isThumbnailCandidate(true, false)).isTrue();
    }
}
