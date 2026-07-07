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
 *
 * Adapted from qupath.ext.qpcat.model.CellRef in QP-CAT
 * (qupath-extension-cell-analysis-tools), licensed Apache-2.0.
 */

package qupath.ext.cluster3d.model;

import java.awt.Shape;

/**
 * Lightweight, index-aligned back-reference from a plotted point to its cell's
 * location in the project. One {@code CellRef} per point in the point cloud
 * (same order as the {@code ax}/{@code ay}/{@code az} arrays in
 * {@link PointCloudData}).
 *
 * <p>Holds only what is needed to navigate to (or crop) the cell: the source
 * image's project-entry id + display name and the ROI centroid in
 * full-resolution pixel coordinates. We intentionally do NOT hold the
 * {@code PathObject} itself -- the UI may outlive the open image, and the
 * centroid is enough for both {@code ViewerNavigator} and
 * {@code CellCropService}. The bounding-box half-extent lets the crop service
 * size a window to a multiple of the cell without re-reading the hierarchy.</p>
 *
 * <p>Optionally also carries a PRECOMPUTED, FLATTENED segmentation outline (a
 * {@link java.awt.Shape} in full-resolution pixel coordinates) plus the cell's
 * packed class color, so the crop service can draw the detection boundary onto a
 * crop without retaining the live {@code ROI} (whose {@code getShape()} lazily
 * initializes and is not safe to call concurrently). The outline is computed
 * once on the single read thread; only JDK types are stored here so the model
 * stays free of any {@code qupath.lib.roi} dependency. Both are optional --
 * {@code roiOutline} may be null and {@code roiColorRgb} may be 0 (no color).</p>
 */
public class CellRef {

    private final String imageId; // ProjectImageEntry.getID(); null for single-image-no-project
    private final String imageName; // display name; may be null on loaded results (looked up by id)
    private final double x; // ROI centroid X, full-resolution pixels
    private final double y; // ROI centroid Y, full-resolution pixels
    private final double bboxHalf; // 0.5 * max(bbox width, bbox height), pixels; <=0 if unknown
    // Precomputed, flattened segmentation outline in full-resolution pixels (nullable).
    private final Shape roiOutline;
    // Packed RGB of the cell's class color for drawing the outline; 0 = none/unknown.
    private final int roiColorRgb;

    /** Back-reference without a segmentation outline (navigation / crop only). */
    public CellRef(String imageId, String imageName, double x, double y, double bboxHalf) {
        this(imageId, imageName, x, y, bboxHalf, null, 0);
    }

    /** Back-reference that also carries a precomputed outline + packed class color. */
    public CellRef(
            String imageId,
            String imageName,
            double x,
            double y,
            double bboxHalf,
            Shape roiOutline,
            int roiColorRgb) {
        this.imageId = imageId;
        this.imageName = imageName;
        this.x = x;
        this.y = y;
        this.bboxHalf = bboxHalf;
        this.roiOutline = roiOutline;
        this.roiColorRgb = roiColorRgb;
    }

    public String getImageId() {
        return imageId;
    }

    public String getImageName() {
        return imageName;
    }

    public double getX() {
        return x;
    }

    public double getY() {
        return y;
    }

    /** Half of the larger bounding-box side, in pixels. Returns 0 if unknown. */
    public double getBboxHalf() {
        return bboxHalf;
    }

    /**
     * Precomputed, flattened segmentation outline in full-resolution pixel
     * coordinates, or {@code null} if none was captured (e.g. a point ROI, a
     * loaded result, or capture failure).
     */
    public Shape getRoiOutline() {
        return roiOutline;
    }

    /** Packed RGB of the cell's class color for the outline; 0 if none/unknown. */
    public int getRoiColorRgb() {
        return roiColorRgb;
    }
}
