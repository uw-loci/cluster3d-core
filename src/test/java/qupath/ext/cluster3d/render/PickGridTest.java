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

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PickGridTest {

    @Test
    void picksNearestWithinRadius() {
        PickGrid grid = new PickGrid(7);
        grid.add(0, 10, 10, 1.0);
        grid.add(1, 50, 50, 1.0);
        grid.add(2, 100, 100, 1.0);
        assertThat(grid.pick(11, 11, 7)).isEqualTo(0);
        assertThat(grid.pick(52, 49, 7)).isEqualTo(1);
    }

    @Test
    void frontMostWinsOnTie() {
        PickGrid grid = new PickGrid(7);
        // Same screen position, different depths: the smaller-depth (front) wins.
        grid.add(0, 30, 30, 5.0);
        grid.add(1, 30, 30, 2.0);
        grid.add(2, 30, 30, 9.0);
        assertThat(grid.pick(30, 30, 7)).isEqualTo(1);
    }

    @Test
    void emptyBucketMissReturnsMinusOne() {
        PickGrid grid = new PickGrid(7);
        grid.add(0, 10, 10, 1.0);
        assertThat(grid.pick(500, 500, 7)).isEqualTo(-1);
    }

    @Test
    void pointJustOutsideRadiusIsMissed() {
        PickGrid grid = new PickGrid(7);
        grid.add(0, 0, 0, 1.0);
        // Distance 10 > radius 7.
        assertThat(grid.pick(10, 0, 7)).isEqualTo(-1);
        // Distance 5 <= radius 7.
        assertThat(grid.pick(5, 0, 7)).isEqualTo(0);
    }

    @Test
    void sizeCountsAllAdded() {
        PickGrid grid = new PickGrid(5);
        grid.add(0, 1, 1, 0);
        grid.add(1, 100, 100, 0);
        grid.add(2, 3, 3, 0);
        assertThat(grid.size()).isEqualTo(3);
    }
}
