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

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** An open preview must leave the class list usable in a short column. */
class PreviewHeightTest {

    @Test
    void theCropShrinksBetweenItsLimits() {
        assertEquals(220, Cluster3DNavigatorPane.previewImageHeight(1000));
        assertEquals(170, Cluster3DNavigatorPane.previewImageHeight(400));
        assertEquals(60, Cluster3DNavigatorPane.previewImageHeight(100));
    }
}
