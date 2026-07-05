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
 */
public class CellRef {

    private final String imageId; // ProjectImageEntry.getID(); null for single-image-no-project
    private final String imageName; // display name; may be null on loaded results (looked up by id)
    private final double x; // ROI centroid X, full-resolution pixels
    private final double y; // ROI centroid Y, full-resolution pixels
    private final double bboxHalf; // 0.5 * max(bbox width, bbox height), pixels; <=0 if unknown

    public CellRef(String imageId, String imageName, double x, double y, double bboxHalf) {
        this.imageId = imageId;
        this.imageName = imageName;
        this.x = x;
        this.y = y;
        this.bboxHalf = bboxHalf;
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
}
