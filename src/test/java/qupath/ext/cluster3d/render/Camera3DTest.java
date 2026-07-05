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
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class Camera3DTest {

    @Test
    void projectionIsDeterministic() {
        Camera3D cam = new Camera3D();
        cam.setViewport(400, 300);
        double[] a = cam.project(0.3, -0.2, 0.5);
        double[] b = cam.project(0.3, -0.2, 0.5);
        assertThat(a).containsExactly(b);
    }

    @Test
    void fitAllCentersTheCloud() {
        Camera3D cam = new Camera3D();
        cam.setViewport(200, 100);
        float[] xs = {-1, 1, 0};
        float[] ys = {-1, 1, 0};
        float[] zs = {-1, 1, 0};
        cam.fitAll(xs, ys, zs);
        // The cloud center (0,0,0) must project to the viewport center regardless of tilt.
        double[] c = cam.project(0, 0, 0);
        assertThat(c[0]).isCloseTo(100.0, within(1e-6));
        assertThat(c[1]).isCloseTo(50.0, within(1e-6));
    }

    @Test
    void resetRestoresDefaultTilt() {
        Camera3D cam = new Camera3D();
        cam.rotate(45, 30);
        cam.zoomAt(2.0, 10, 10);
        cam.pan(20, 20);
        cam.reset();
        assertThat(cam.getYaw()).isEqualTo(Camera3D.DEFAULT_YAW);
        assertThat(cam.getPitch()).isEqualTo(Camera3D.DEFAULT_PITCH);
        assertThat(cam.getZoom()).isEqualTo(1.0);
        assertThat(cam.getPanX()).isEqualTo(0.0);
        assertThat(cam.getPanY()).isEqualTo(0.0);
    }

    @Test
    void pitchIsClamped() {
        Camera3D cam = new Camera3D();
        cam.setPitch(200);
        assertThat(cam.getPitch()).isEqualTo(Camera3D.PITCH_LIMIT);
        cam.setPitch(-200);
        assertThat(cam.getPitch()).isEqualTo(-Camera3D.PITCH_LIMIT);
    }

    @Test
    void axesMapToExpectedScreenDirections() {
        Camera3D cam = new Camera3D();
        cam.setViewport(200, 200);
        float[] pts = {-1, 1};
        cam.fitAll(pts, pts, pts);
        cam.setYaw(0);
        cam.setPitch(0);
        double[] center = cam.project(0, 0, 0);
        double[] plusX = cam.project(0.5, 0, 0);
        double[] plusY = cam.project(0, 0.5, 0);
        // +X moves right on screen; +Y moves up (smaller screen-y).
        assertThat(plusX[0]).isGreaterThan(center[0]);
        assertThat(plusX[1]).isCloseTo(center[1], within(1e-6));
        assertThat(plusY[1]).isLessThan(center[1]);
    }
}
