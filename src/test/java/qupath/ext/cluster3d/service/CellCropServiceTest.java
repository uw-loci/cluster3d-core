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

package qupath.ext.cluster3d.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.awt.Shape;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import qupath.ext.cluster3d.service.CellCropService.CropWindow;

class CellCropServiceTest {

    @Test
    void cropWindowSizesToBoundingBoxTimesScale() {
        // bboxHalf 20, scale 3 -> half 60, side 120, centered at (500,500).
        CropWindow w = CellCropService.computeCropWindow(1000, 1000, 500, 500, 20, 3.0);
        assertThat(w.side).isEqualTo(120);
        assertThat(w.x).isEqualTo(440);
        assertThat(w.y).isEqualTo(440);
    }

    @Test
    void cropWindowClampsToImageBounds() {
        // Near top-left corner: the window must not go negative.
        CropWindow w = CellCropService.computeCropWindow(1000, 1000, 10, 10, 20, 3.0);
        assertThat(w.x).isEqualTo(0);
        assertThat(w.y).isEqualTo(0);
        assertThat(w.side).isEqualTo(120);
    }

    @Test
    void cropWindowUsesFallbackWhenBboxUnknown() {
        // bboxHalf <= 0 -> fallback 40px half * scale.
        CropWindow w = CellCropService.computeCropWindow(2000, 2000, 500, 500, 0, 2.0);
        assertThat(w.side).isEqualTo(160);
    }

    @Test
    void cropWindowSideBoundedByImage() {
        // Requested side larger than image -> clamped to the smaller dimension.
        CropWindow w = CellCropService.computeCropWindow(100, 60, 50, 30, 200, 3.0);
        assertThat(w.side).isEqualTo(60);
    }

    @Test
    void downsampleAtLeastOne() {
        CropWindow w = CellCropService.computeCropWindow(1000, 1000, 500, 500, 5, 1.0);
        assertThat(w.downsample).isGreaterThanOrEqualTo(1.0);
    }

    // --- bounded-LRU eviction policy (server cache) ---

    @Test
    void boundedLruEvictsAndClosesEldestOverCapacity() {
        List<String> evicted = new ArrayList<>();
        Map<String, String> m = CellCropService.boundedLru(2, evicted::add);

        m.put("a", "A");
        m.put("b", "B");
        assertThat(evicted).isEmpty();

        m.put("c", "C"); // over capacity -> evict + "close" the eldest ("a").
        assertThat(evicted).containsExactly("A");
        assertThat(m.keySet()).containsExactlyInAnyOrder("b", "c");
    }

    @Test
    void canApplyViewerDisplayOnlyWhenChannelCountsMatch() {
        assertThat(CellCropService.canApplyViewerDisplay(3, 3)).isTrue();
        assertThat(CellCropService.canApplyViewerDisplay(1, 1)).isTrue();
        assertThat(CellCropService.canApplyViewerDisplay(3, 4)).isFalse();
        assertThat(CellCropService.canApplyViewerDisplay(4, 3)).isFalse();
    }

    // --- outline -> crop-space transform (full-res px -> crop px = (p - origin)/downsample) ---

    @Test
    void outlineToCropSpaceIdentityLeavesBoundsUnchanged() {
        Shape s = new Rectangle2D.Double(100, 100, 10, 10);
        Rectangle2D b = CellCropService.outlineToCropSpace(s, 0, 0, 1.0).getBounds2D();
        assertThat(b.getMinX()).isCloseTo(100, within(1e-9));
        assertThat(b.getMinY()).isCloseTo(100, within(1e-9));
        assertThat(b.getWidth()).isCloseTo(10, within(1e-9));
        assertThat(b.getHeight()).isCloseTo(10, within(1e-9));
    }

    @Test
    void outlineToCropSpaceTranslatesByCropOrigin() {
        // Full-res rect (100,100)-(110,110), crop origin (90,90), d=1 -> (10,10)-(20,20).
        Shape s = new Rectangle2D.Double(100, 100, 10, 10);
        Rectangle2D b = CellCropService.outlineToCropSpace(s, 90, 90, 1.0).getBounds2D();
        assertThat(b.getMinX()).isCloseTo(10, within(1e-9));
        assertThat(b.getMinY()).isCloseTo(10, within(1e-9));
        assertThat(b.getMaxX()).isCloseTo(20, within(1e-9));
        assertThat(b.getMaxY()).isCloseTo(20, within(1e-9));
    }

    @Test
    void outlineToCropSpaceTranslatesThenScales() {
        // Same rect, origin (90,90), d=2 -> translate first ((10,10)-(20,20)) then /2 -> (5,5)-(10,10).
        Shape s = new Rectangle2D.Double(100, 100, 10, 10);
        Rectangle2D b = CellCropService.outlineToCropSpace(s, 90, 90, 2.0).getBounds2D();
        assertThat(b.getMinX()).isCloseTo(5, within(1e-9));
        assertThat(b.getMinY()).isCloseTo(5, within(1e-9));
        assertThat(b.getMaxX()).isCloseTo(10, within(1e-9));
        assertThat(b.getMaxY()).isCloseTo(10, within(1e-9));
    }

    @Test
    void outlineToCropSpaceNullIsNull() {
        assertThat(CellCropService.outlineToCropSpace(null, 0, 0, 1.0)).isNull();
    }

    @Test
    void boundedLruIsAccessOrdered() {
        List<String> evicted = new ArrayList<>();
        Map<String, String> m = CellCropService.boundedLru(2, evicted::add);
        m.put("a", "A");
        m.put("b", "B");
        // Touch "a" so it becomes most-recently-used; adding "c" must then evict "b".
        m.get("a");
        m.put("c", "C");
        assertThat(evicted).containsExactly("B");
        assertThat(m.keySet()).containsExactlyInAnyOrder("a", "c");
    }
}
