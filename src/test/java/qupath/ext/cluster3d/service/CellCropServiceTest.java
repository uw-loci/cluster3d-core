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
