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

package qupath.ext.cluster3d.prefs;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.StringProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.gui.prefs.PathPrefs;

/**
 * Persistent preferences for the Cluster 3D Navigator.
 *
 * <p>Persists: window geometry (x/y/w/h), last mode, last X/Y/Z axis names per
 * project, and the Display-options values (crop scale, point size, depth-cue,
 * tripod). Does NOT persist camera viewpoint or the hidden-class set (Lead
 * decision). Pattern source: {@code CMPreferences} / {@code FiberAnalysisPreferences}.</p>
 */
public final class Cluster3DNavPreferences {

    private static final Logger logger = LoggerFactory.getLogger(Cluster3DNavPreferences.class);
    private static final String PREFIX = "cluster3dnav.";

    // === Defaults ===
    public static final double DEFAULT_WINDOW_X = -1; // -1 => center on first open
    public static final double DEFAULT_WINDOW_Y = -1;
    public static final double DEFAULT_WINDOW_W = 1040;
    public static final double DEFAULT_WINDOW_H = 680;
    public static final String DEFAULT_MODE = "current"; // "current" | "project"
    public static final double DEFAULT_CROP_SCALE = 3.0;
    public static final double DEFAULT_POINT_SIZE = 2.0;
    public static final boolean DEFAULT_DEPTH_CUE = true;
    public static final boolean DEFAULT_SHOW_TRIPOD = true;
    public static final boolean DEFAULT_HOVER_PREVIEW = false;
    public static final boolean DEFAULT_SHOW_CELL_IMAGES = false;
    public static final int DEFAULT_REPRESENTATIVES_PER_CLUSTER = 3;
    public static final int DEFAULT_CELL_LIMIT_PER_IMAGE = 0; // 0 = no limit
    public static final int DEFAULT_SUBSAMPLE_SEED = 42;

    private static DoubleProperty windowX;
    private static DoubleProperty windowY;
    private static DoubleProperty windowW;
    private static DoubleProperty windowH;
    private static StringProperty mode;
    private static DoubleProperty cropScale;
    private static DoubleProperty pointSize;
    private static BooleanProperty depthCue;
    private static BooleanProperty showTripod;
    private static BooleanProperty hoverPreview;
    private static BooleanProperty showCellImages;
    private static IntegerProperty representativesPerCluster;
    private static IntegerProperty cellLimitPerImage;
    private static IntegerProperty subsampleSeed;

    private static boolean installed = false;

    private Cluster3DNavPreferences() {}

    /** Installs persistent preferences. Idempotent. */
    public static synchronized void installPreferences() {
        if (installed) {
            return;
        }
        logger.info("Installing Cluster 3D Navigator preferences");

        windowX = PathPrefs.createPersistentPreference(PREFIX + "windowX", DEFAULT_WINDOW_X);
        windowY = PathPrefs.createPersistentPreference(PREFIX + "windowY", DEFAULT_WINDOW_Y);
        windowW = PathPrefs.createPersistentPreference(PREFIX + "windowW", DEFAULT_WINDOW_W);
        windowH = PathPrefs.createPersistentPreference(PREFIX + "windowH", DEFAULT_WINDOW_H);
        mode = PathPrefs.createPersistentPreference(PREFIX + "mode", DEFAULT_MODE);
        cropScale = PathPrefs.createPersistentPreference(PREFIX + "cropScale", DEFAULT_CROP_SCALE);
        pointSize = PathPrefs.createPersistentPreference(PREFIX + "pointSize", DEFAULT_POINT_SIZE);
        depthCue = PathPrefs.createPersistentPreference(PREFIX + "depthCue", DEFAULT_DEPTH_CUE);
        showTripod = PathPrefs.createPersistentPreference(PREFIX + "showTripod", DEFAULT_SHOW_TRIPOD);
        hoverPreview = PathPrefs.createPersistentPreference(PREFIX + "hoverPreview", DEFAULT_HOVER_PREVIEW);
        showCellImages = PathPrefs.createPersistentPreference(PREFIX + "showCellImages", DEFAULT_SHOW_CELL_IMAGES);
        representativesPerCluster = PathPrefs.createPersistentPreference(
                PREFIX + "representativesPerCluster", DEFAULT_REPRESENTATIVES_PER_CLUSTER);
        cellLimitPerImage =
                PathPrefs.createPersistentPreference(PREFIX + "cellLimitPerImage", DEFAULT_CELL_LIMIT_PER_IMAGE);
        subsampleSeed = PathPrefs.createPersistentPreference(PREFIX + "subsampleSeed", DEFAULT_SUBSAMPLE_SEED);

        installed = true;
        logger.info("Cluster 3D Navigator preferences installed");
    }

    private static void ensureInstalled() {
        if (!installed) {
            installPreferences();
        }
    }

    public static DoubleProperty windowXProperty() {
        ensureInstalled();
        return windowX;
    }

    public static DoubleProperty windowYProperty() {
        ensureInstalled();
        return windowY;
    }

    public static DoubleProperty windowWProperty() {
        ensureInstalled();
        return windowW;
    }

    public static DoubleProperty windowHProperty() {
        ensureInstalled();
        return windowH;
    }

    public static StringProperty modeProperty() {
        ensureInstalled();
        return mode;
    }

    public static DoubleProperty cropScaleProperty() {
        ensureInstalled();
        return cropScale;
    }

    public static DoubleProperty pointSizeProperty() {
        ensureInstalled();
        return pointSize;
    }

    public static BooleanProperty depthCueProperty() {
        ensureInstalled();
        return depthCue;
    }

    public static BooleanProperty showTripodProperty() {
        ensureInstalled();
        return showTripod;
    }

    public static BooleanProperty hoverPreviewProperty() {
        ensureInstalled();
        return hoverPreview;
    }

    public static IntegerProperty representativesPerClusterProperty() {
        ensureInstalled();
        return representativesPerCluster;
    }

    public static IntegerProperty cellLimitPerImageProperty() {
        ensureInstalled();
        return cellLimitPerImage;
    }

    public static IntegerProperty subsampleSeedProperty() {
        ensureInstalled();
        return subsampleSeed;
    }

    public static BooleanProperty showCellImagesProperty() {
        ensureInstalled();
        return showCellImages;
    }

    // === Per-project last-axis names. Persisted under a project-scoped key so
    // different projects remember different embeddings. ===

    // Package-private for regression testing of the 80-char key-length cap.
    static String axisKey(String projectKey, String axis) {
        // Java Preferences keys are capped at 80 chars (Preferences.MAX_KEY_LENGTH).
        // A project key is a full file path, so embedding it verbatim overflows the
        // limit and throws. Hash it to a short, stable, fixed-length token instead.
        String pk = (projectKey == null || projectKey.isBlank()) ? "default" : shortHash(projectKey);
        return PREFIX + "axis." + pk + "." + axis;
    }

    /**
     * Stable, short, filesystem/pref-safe token for a project identifier. Uses a
     * 64-bit FNV-1a hash rendered as hex (max 16 chars) so the full preference key
     * stays well under the 80-char Preferences limit regardless of project path length.
     * Collisions across a user's handful of projects are negligible and harmless
     * (worst case two projects share remembered axes).
     */
    static String shortHash(String s) {
        long h = 0xcbf29ce484222325L; // FNV-1a offset basis
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 0x100000001b3L; // FNV-1a prime
        }
        return Long.toHexString(h);
    }

    /** Store the last X/Y/Z axis names for a project (projectKey may be null). */
    public static void setLastAxes(String projectKey, String x, String y, String z) {
        ensureInstalled();
        PathPrefs.createPersistentPreference(axisKey(projectKey, "x"), "").set(x == null ? "" : x);
        PathPrefs.createPersistentPreference(axisKey(projectKey, "y"), "").set(y == null ? "" : y);
        PathPrefs.createPersistentPreference(axisKey(projectKey, "z"), "").set(z == null ? "" : z);
    }

    /** Read the last X/Y/Z axis names for a project, or an array of empty strings. */
    public static String[] getLastAxes(String projectKey) {
        ensureInstalled();
        return new String[] {
            PathPrefs.createPersistentPreference(axisKey(projectKey, "x"), "").get(),
            PathPrefs.createPersistentPreference(axisKey(projectKey, "y"), "").get(),
            PathPrefs.createPersistentPreference(axisKey(projectKey, "z"), "").get()
        };
    }
}
