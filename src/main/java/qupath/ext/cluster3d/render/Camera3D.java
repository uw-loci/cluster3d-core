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

/**
 * Pure 3D camera math: yaw / pitch / zoom / pan mapping a 3D point to a 2D
 * screen position plus a depth value. No JavaFX Scene dependency, so it is fully
 * unit-testable with plain doubles.
 *
 * <p>The projection is orthographic. A world point is translated by the fit-all
 * center, rotated by yaw (about the vertical Y axis) then pitch (about the
 * horizontal X axis), scaled by the fit-all base scale times the interactive
 * zoom, and offset by the pan. Screen Y is inverted (screen grows downward).</p>
 *
 * <p>{@link #project} returns {@code {screenX, screenY, depth}}. Depth increases
 * away from the camera, so the front-most point has the smallest depth -- the
 * painter's algorithm draws largest depth first, and picking breaks ties toward
 * the smallest depth.</p>
 */
public final class Camera3D {

    /** Default yaw (degrees) for the fit-all isometric-ish tilt. */
    public static final double DEFAULT_YAW = 30.0;
    /** Default pitch (degrees) for the fit-all isometric-ish tilt. */
    public static final double DEFAULT_PITCH = -20.0;
    /** Pitch is clamped to +/- this many degrees to avoid gimbal flip. */
    public static final double PITCH_LIMIT = 89.0;
    /** Fraction of the smaller viewport dimension the fit-all cloud spans. */
    private static final double FIT_MARGIN = 0.85;

    private double yaw = DEFAULT_YAW;
    private double pitch = DEFAULT_PITCH;
    private double zoom = 1.0;
    private double panX = 0.0;
    private double panY = 0.0;

    private double viewportW = 1.0;
    private double viewportH = 1.0;

    // Fit-all state: cloud center and base scale (world units -> screen px at zoom 1).
    private double centerX = 0.0;
    private double centerY = 0.0;
    private double centerZ = 0.0;
    private double baseScale = 1.0;

    /** Set the drawable area size (px). Safe to call on every resize. */
    public void setViewport(double width, double height) {
        this.viewportW = Math.max(1.0, width);
        this.viewportH = Math.max(1.0, height);
    }

    public double getViewportWidth() {
        return viewportW;
    }

    public double getViewportHeight() {
        return viewportH;
    }

    /**
     * Compute the fit-all center and base scale from the point arrays so the whole
     * cloud fits in the current viewport. Does not change yaw/pitch/zoom/pan; call
     * {@link #reset()} to also restore the default tilt and clear zoom/pan.
     */
    public void fitAll(float[] xs, float[] ys, float[] zs) {
        if (xs == null || xs.length == 0) {
            centerX = centerY = centerZ = 0.0;
            baseScale = 1.0;
            return;
        }
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
        double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        double minZ = Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (int i = 0; i < xs.length; i++) {
            minX = Math.min(minX, xs[i]);
            maxX = Math.max(maxX, xs[i]);
            minY = Math.min(minY, ys[i]);
            maxY = Math.max(maxY, ys[i]);
            minZ = Math.min(minZ, zs[i]);
            maxZ = Math.max(maxZ, zs[i]);
        }
        centerX = (minX + maxX) / 2.0;
        centerY = (minY + maxY) / 2.0;
        centerZ = (minZ + maxZ) / 2.0;

        // Use the full diagonal extent so the cloud fits at any rotation.
        double dx = maxX - minX;
        double dy = maxY - minY;
        double dz = maxZ - minZ;
        double extent = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (extent <= 0) {
            extent = 1.0;
        }
        double minDim = Math.min(viewportW, viewportH);
        baseScale = (minDim * FIT_MARGIN) / extent;
    }

    /** Restore yaw/pitch to the default tilt and clear zoom + pan (keeps fit-all). */
    public void reset() {
        yaw = DEFAULT_YAW;
        pitch = DEFAULT_PITCH;
        zoom = 1.0;
        panX = 0.0;
        panY = 0.0;
    }

    /**
     * Project a world point to {@code {screenX, screenY, depth}}. Deterministic:
     * identical inputs and camera state always yield identical output.
     */
    public double[] project(double x, double y, double z) {
        double dx = x - centerX;
        double dy = y - centerY;
        double dz = z - centerZ;

        double yawR = Math.toRadians(yaw);
        double pitchR = Math.toRadians(pitch);
        double cy = Math.cos(yawR), sy = Math.sin(yawR);
        double cp = Math.cos(pitchR), sp = Math.sin(pitchR);

        // Yaw about Y.
        double x1 = dx * cy + dz * sy;
        double z1 = -dx * sy + dz * cy;
        double y1 = dy;

        // Pitch about X.
        double y2 = y1 * cp - z1 * sp;
        double z2 = y1 * sp + z1 * cp;
        double x2 = x1;

        double s = baseScale * zoom;
        double screenX = viewportW / 2.0 + panX + x2 * s;
        double screenY = viewportH / 2.0 + panY - y2 * s;
        return new double[] {screenX, screenY, z2};
    }

    /** Rotate the camera by screen-drag deltas (degrees), clamping pitch. */
    public void rotate(double deltaYawDeg, double deltaPitchDeg) {
        yaw = (yaw + deltaYawDeg) % 360.0;
        setPitch(pitch + deltaPitchDeg);
    }

    /** Pan the projected cloud by a screen offset (px). */
    public void pan(double deltaScreenX, double deltaScreenY) {
        panX += deltaScreenX;
        panY += deltaScreenY;
    }

    /**
     * Zoom by {@code factor} while keeping the world point currently under the
     * cursor fixed on screen (dolly-at-cursor). {@code factor < 1} zooms out.
     */
    public void zoomAt(double factor, double cursorX, double cursorY) {
        if (factor <= 0) {
            return;
        }
        double newZoom = zoom * factor;
        double ox = cursorX - viewportW / 2.0;
        double oy = cursorY - viewportH / 2.0;
        // World-scaled offset that currently lands under the cursor.
        double wx = (ox - panX) / zoom;
        double wy = (oy - panY) / zoom;
        panX = ox - wx * newZoom;
        panY = oy - wy * newZoom;
        zoom = newZoom;
    }

    public double getYaw() {
        return yaw;
    }

    public double getPitch() {
        return pitch;
    }

    public void setPitch(double pitchDeg) {
        this.pitch = Math.max(-PITCH_LIMIT, Math.min(PITCH_LIMIT, pitchDeg));
    }

    public void setYaw(double yawDeg) {
        this.yaw = yawDeg % 360.0;
    }

    public double getZoom() {
        return zoom;
    }

    public double getPanX() {
        return panX;
    }

    public double getPanY() {
        return panY;
    }

    public double getBaseScale() {
        return baseScale;
    }
}
