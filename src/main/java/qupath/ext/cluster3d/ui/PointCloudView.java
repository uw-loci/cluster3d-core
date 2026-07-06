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
 * Adapted from the Canvas interaction idioms of qupath.ext.qpcat.ui.EmbeddingScatterPanel in QP-CAT
 * (qupath-extension-cell-analysis-tools), licensed Apache-2.0.
 */

package qupath.ext.cluster3d.ui;

import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;
import javafx.util.Duration;
import qupath.ext.cluster3d.io.DetectionReader;
import qupath.ext.cluster3d.model.CellRef;
import qupath.ext.cluster3d.model.PointCloudData;
import qupath.ext.cluster3d.render.Camera3D;
import qupath.ext.cluster3d.render.PickGrid;
import qupath.ext.cluster3d.service.ViewerNavigator;
import qupath.lib.gui.QuPathGUI;

/**
 * The 3D point-cloud Canvas panel. Holds a {@link Camera3D} and the current
 * {@link PointCloudData}; the draw loop projects every visible point, depth-sorts
 * with the painter's algorithm, and paints with optional depth-cue shading and an
 * axis tripod. A {@link PickGrid} is rebuilt each repaint for O(1)-ish picking.
 *
 * <p>Gestures: left-drag rotate, scroll zoom-at-cursor, middle-drag pan; a
 * left-click without a drag (below a small pixel threshold) selects the nearest
 * point, navigates the viewer to that cell, and requests a crop preview. Hover is
 * throttled and shows a tooltip; a crop-on-hover is only requested when the
 * advanced toggle is on. The background is theme-aware (dark gray on the QuPath
 * dark theme).</p>
 */
public class PointCloudView extends Pane {

    private static final int PICK_RADIUS = 7; // internal constant (Lead decision)
    private static final double CLICK_THRESHOLD_PX = 4.0;
    private static final double ROTATE_SPEED = 0.4; // degrees per screen px
    private static final double HOVER_MOVE_GUARD_SQ = 9.0; // 3px

    // --- Cell-image LOD (VEST-style thumbnails when zoomed in) ---
    // Nominal on-screen size a single cell "owns" in normalized world units; the
    // per-frame footprint = baseScale * zoom * this. Tuned so thumbnails appear
    // only after the user has clearly zoomed past the fit-all view.
    private static final double CELL_WORLD_SIZE = 0.045;
    // Only draw a thumbnail once a cell's footprint reaches this many px.
    private static final double MIN_FOOTPRINT_PX = 14.0;
    // Clamp the drawn thumbnail size so an extreme zoom does not draw giant images.
    private static final double MAX_THUMB_PX = 120.0;
    // Monotonic scale for packing a depth key into a sort long (fits in 31 bits).
    private static final double DEPTH_SORT_SCALE = 1 << 30;
    // A candidate thumbnail is suppressed if it overlaps any already-accepted (nearer)
    // thumbnail by MORE than this fraction of its area -- so the visible thumbnails
    // never overlap by more than ~10%.
    private static final double MAX_THUMB_OVERLAP_FRAC = 0.10;
    // Ordered representative cells computed per class (medoid + farthest-point spread).
    private static final int MAX_REPRESENTATIVES = 5;
    // Bounded LRU cache of decoded crop images. With viewport culling only the
    // on-screen thumbnails need to stay cached, so this is small (each entry is a
    // decoded ~64-128px image; 400 keeps decoded-crop heap well under ~100 MB).
    private static final int IMAGE_CACHE_SIZE = 400;
    // Cap crop loads enqueued per frame so a fresh zoom-in does not submit a burst.
    private static final int MAX_LOADS_PER_FRAME = 128;
    // Global cap on concurrently in-flight crop loads: skip enqueue above this so the
    // executor queue + loadingIndices cannot balloon during fast interaction.
    private static final int MAX_IN_FLIGHT = 160;
    // Loader threads for off-thread crop reads.
    private static final int LOADER_THREADS = 4;
    // A cell is an on-screen thumbnail candidate only if its projected point is within
    // the canvas bounds expanded by this many px (so a partly-visible thumbnail still
    // draws). Off-screen cells stay points and are never enqueued.
    private static final double ON_SCREEN_MARGIN_PX = MAX_THUMB_PX;

    private final Canvas canvas = new Canvas();
    private final Camera3D camera = new Camera3D();
    private final Tooltip tooltip = new Tooltip();
    private final QuPathGUI qupath;

    private PointCloudData data;
    private boolean[] visibleMask = new boolean[0]; // per class

    // Projected screen coords + depth-sorted visible order. Recomputed only when the
    // projection is dirty (camera / data / visibility change), NOT on every redraw --
    // a crop-load-completion repaint reuses the cached projection instead of re-sorting.
    private double[] projX = new double[0];
    private double[] projY = new double[0];
    private double[] projDepth = new double[0];
    private int[] drawOrder = new int[0]; // visible cell indices, far-to-near (primitive; reused)
    private long[] sortBuf = new long[0]; // reused primitive sort buffer (depthKey<<32 | index)
    private int visM = 0; // number of visible cells in drawOrder
    private double cachedMinDepth = 0.0;
    private double cachedDRange = 1.0;
    private boolean projectionDirty = true;
    private PickGrid pickGrid;

    // Display options.
    private double pointSize = 2.0;
    private boolean depthCue = true;
    private boolean showTripod = true;
    private Color backgroundColor = null; // null = auto (match QuPath theme)
    private boolean hoverPreviewEnabled = false;
    private boolean showCellImages = false;
    // Flat 2D mode: no rotation (left-drag pans), Z ignored, no Z tooltip/tripod line.
    private boolean twoD = false;
    // Per-class ordered representative cell indices (medoid + farthest-point spread);
    // classReps[c] length <= MAX_REPRESENTATIVES. Recomputed on setData.
    private int[][] classReps = new int[0][];
    // repCandidate[i] == true if cell i is among the first repsPerCluster of its class;
    // those cells are thumbnail candidates at ANY zoom. Rebuilt on setData / K change.
    private boolean[] repCandidate = new boolean[0];
    private int repsPerCluster = 3;
    // Reused buffer for the far-to-near candidate order fed to the occlusion pass.
    private int[] candBuf = new int[0];

    // --- Cell-image LOD state ---
    /** Supplies a crop (off the FX thread) for a cell + scale. Wired by the window. */
    public interface CropLoader {
        BufferedImage load(CellRef ref, double cropScale);
    }

    private CropLoader cropLoader;
    private DoubleSupplier cropScaleSupplier = () -> 3.0;
    // Bounded LRU cache of decoded crop images, keyed by point index. FX-thread only.
    private final LinkedHashMap<Integer, Image> imageCache = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, Image> eldest) {
            return size() > IMAGE_CACHE_SIZE;
        }
    };
    // Indices with an in-flight crop load (dedup). FX-thread only.
    private final java.util.Set<Integer> loadingIndices = new java.util.HashSet<>();
    // Generation counter so an in-flight load for a stale cache/dataset is discarded.
    private int imageCacheGen = 0;
    // Coalesces redraws: many crop-load completions in a burst schedule at most ONE
    // redraw (set on the FX thread; cleared when the scheduled redraw runs).
    private boolean redrawScheduled = false;
    // Set true once shutdown() runs. A crop completion's Platform.runLater (or a
    // scheduled redraw) can still fire on the FX thread AFTER the executor is
    // terminated; without this guard the follow-on enqueueCrop() would submit to the
    // dead pool and throw RejectedExecutionException on window close.
    private volatile boolean disposed = false;
    private final ExecutorService cropExecutor = Executors.newFixedThreadPool(LOADER_THREADS, r -> {
        Thread t = new Thread(r, "cluster3dnav-thumb");
        t.setDaemon(true);
        return t;
    });

    // Last cursor position over the canvas (for prioritizing crop loads by what the user
    // is looking at); NaN until the mouse moves over the canvas.
    private double lastCursorX = Double.NaN;
    private double lastCursorY = Double.NaN;

    // TRUE while the currently-visible thumbnail set is still loading (loads in flight or
    // selected-but-uncached cells). Debounced off so the banner does not flicker.
    private final SimpleBooleanProperty populating = new SimpleBooleanProperty(false);
    private final PauseTransition populatingHideDelay = new PauseTransition(Duration.millis(300));

    private int selectedIndex = -1;
    private String emptyMessage = null;

    // Drag bookkeeping.
    private double dragStartX, dragStartY, lastDragX, lastDragY;
    private double dragTotal;
    private MouseButton dragButton;

    // Hover throttle.
    private double lastHoverX = Double.NaN, lastHoverY = Double.NaN;
    private int lastHoverIdx = Integer.MIN_VALUE;

    // Callbacks (window wires crop preview + caption).
    private Consumer<Integer> onPointClicked = i -> {};
    private Consumer<Integer> onPointHovered = i -> {};

    public PointCloudView(QuPathGUI qupath) {
        this.qupath = qupath;
        populatingHideDelay.setOnFinished(e -> populating.set(false));
        getChildren().add(canvas);
        tooltip.setShowDelay(Duration.millis(200));
        tooltip.setText(gestureHint());
        Tooltip.install(canvas, tooltip);
        canvas.setFocusTraversable(true);
        canvas.setAccessibleText(accessibilityText());

        canvas.setOnScroll(this::onScroll);
        canvas.setOnMousePressed(this::onMousePressed);
        canvas.setOnMouseDragged(this::onMouseDragged);
        canvas.setOnMouseReleased(this::onMouseReleased);
        canvas.setOnMouseMoved(this::onMouseMoved);

        widthProperty().addListener((o, a, b) -> resizeCanvas());
        heightProperty().addListener((o, a, b) -> resizeCanvas());
    }

    /** Mark the cached projection stale so the next redraw re-projects + re-sorts. */
    private void invalidateProjection() {
        projectionDirty = true;
    }

    private void resizeCanvas() {
        canvas.setWidth(getWidth());
        canvas.setHeight(getHeight());
        camera.setViewport(getWidth(), getHeight());
        invalidateProjection();
        redraw();
    }

    /** Load a new dataset and fit the camera to it. */
    public void setData(PointCloudData data) {
        this.data = data;
        this.selectedIndex = -1;
        this.lastHoverIdx = Integer.MIN_VALUE;
        clearImageCache(); // point indices change with a new dataset
        computeClassReps(); // per-class representative cells (medoid + farthest-point spread)
        rebuildRepCandidates();
        if (data != null) {
            camera.setViewport(canvas.getWidth(), canvas.getHeight());
            camera.fitAll(data.ax, data.ay, data.az);
            camera.reset();
        }
        invalidateProjection();
        redraw();
        prewarmRepresentatives(); // populate the always-on far-out set without needing interaction
    }

    /** TRUE while the currently-visible thumbnail set is still loading (debounced). */
    public ReadOnlyBooleanProperty populatingProperty() {
        return populating;
    }

    /**
     * Number of representative cells per cluster shown as thumbnails at ANY zoom (0..5;
     * 0 restores zoom-only behavior). Cheap: re-slices the precomputed per-class lists,
     * no re-sampling.
     */
    public void setRepresentativesPerCluster(int k) {
        this.repsPerCluster = Math.max(0, Math.min(MAX_REPRESENTATIVES, k));
        rebuildRepCandidates();
        redraw();
        prewarmRepresentatives();
    }

    /**
     * Eagerly enqueue the per-cluster representative crops (the always-on far-out set) so the
     * initial far-out view populates without waiting for camera interaction. Bounded by the
     * in-flight cap; no-op when the cell-images feature is off or the crop source is unwired.
     */
    private void prewarmRepresentatives() {
        if (data == null || cropLoader == null || !showCellImages) {
            return;
        }
        int n = data.size();
        for (int i = 0; i < n && loadingIndices.size() < MAX_IN_FLIGHT; i++) {
            if (isRepCandidate(i)) {
                enqueueCrop(i);
            }
        }
    }

    /** Compute up to MAX_REPRESENTATIVES ordered representatives per class via farthest-point sampling. */
    private void computeClassReps() {
        if (data == null || data.classCount() == 0) {
            classReps = new int[0][];
            return;
        }
        int nClasses = data.classCount();
        int n = data.size();
        int[] counts = new int[nClasses];
        for (int i = 0; i < n; i++) {
            int c = data.classIdx[i];
            if (c >= 0 && c < nClasses) {
                counts[c]++;
            }
        }
        int[][] members = new int[nClasses][];
        int[] fill = new int[nClasses];
        for (int c = 0; c < nClasses; c++) {
            members[c] = new int[counts[c]];
        }
        for (int i = 0; i < n; i++) {
            int c = data.classIdx[i];
            if (c >= 0 && c < nClasses) {
                members[c][fill[c]++] = i;
            }
        }
        classReps = new int[nClasses][];
        for (int c = 0; c < nClasses; c++) {
            classReps[c] =
                    DetectionReader.farthestPointSample(data.ax, data.ay, data.az, members[c], MAX_REPRESENTATIVES);
        }
    }

    /** Rebuild the per-cell rep-candidate mask = first {@code repsPerCluster} of each class's list. */
    private void rebuildRepCandidates() {
        int n = data == null ? 0 : data.size();
        if (repCandidate.length != n) {
            repCandidate = new boolean[n];
        } else {
            java.util.Arrays.fill(repCandidate, false);
        }
        for (int[] reps : classReps) {
            if (reps == null) {
                continue;
            }
            int take = Math.min(repsPerCluster, reps.length);
            for (int j = 0; j < take; j++) {
                repCandidate[reps[j]] = true;
            }
        }
    }

    private boolean isRepCandidate(int i) {
        return i >= 0 && i < repCandidate.length && repCandidate[i];
    }

    /**
     * Switch between the rotatable 3D cloud and a flat top-down 2D scatter. In 2D the
     * camera ignores rotation + Z (left-drag pans), and the Z tooltip/tripod line is hidden.
     * Call before {@link #setData} so the fit-all uses the correct (X/Y-only) extent.
     */
    public void setTwoD(boolean twoD) {
        this.twoD = twoD;
        camera.setMode2D(twoD);
        if (data != null) {
            camera.fitAll(data.ax, data.ay, data.az);
            camera.reset();
        }
        tooltip.setText(gestureHint());
        canvas.setAccessibleText(accessibilityText());
        invalidateProjection();
        redraw();
    }

    /** Enable/disable VEST-style in-cloud cell thumbnails (shown only when zoomed in). */
    public void setShowCellImages(boolean showCellImages) {
        this.showCellImages = showCellImages;
        redraw();
        if (showCellImages) {
            prewarmRepresentatives(); // turning the feature on should populate reps immediately
        }
    }

    /** Wire the crop source used to lazily load in-cloud thumbnails (off the FX thread). */
    public void setCropSource(CropLoader loader, DoubleSupplier cropScaleSupplier) {
        this.cropLoader = loader;
        this.cropScaleSupplier = cropScaleSupplier == null ? () -> 3.0 : cropScaleSupplier;
    }

    /**
     * Drop all cached thumbnail images (and abandon in-flight loads via a generation
     * bump) so a display-settings change re-renders the in-cloud thumbnails. Called
     * by the window's "Update from viewer" refresh and on dataset change / hide.
     */
    public void clearImageCache() {
        imageCacheGen++;
        imageCache.clear();
        loadingIndices.clear();
        redraw();
    }

    /** Release the crop executor. Call on window hide. */
    public void shutdown() {
        disposed = true;
        cropExecutor.shutdownNow();
    }

    public void setVisibleMask(boolean[] mask) {
        this.visibleMask = mask == null ? new boolean[0] : mask;
        invalidateProjection(); // the visible set (and its depth order) changed
        redraw();
    }

    public void setPointSize(double pointSize) {
        this.pointSize = pointSize;
        redraw();
    }

    public void setDepthCue(boolean depthCue) {
        this.depthCue = depthCue;
        redraw();
    }

    public void setShowTripod(boolean showTripod) {
        this.showTripod = showTripod;
        redraw();
    }

    /** Set the canvas background color, or {@code null} to match the QuPath theme. */
    public void setBackgroundColor(Color backgroundColor) {
        this.backgroundColor = backgroundColor;
        redraw();
    }

    public void setHoverPreviewEnabled(boolean enabled) {
        this.hoverPreviewEnabled = enabled;
    }

    public void setEmptyMessage(String message) {
        this.emptyMessage = message;
        redraw();
    }

    public void setOnPointClicked(Consumer<Integer> handler) {
        this.onPointClicked = handler == null ? i -> {} : handler;
    }

    public void setOnPointHovered(Consumer<Integer> handler) {
        this.onPointHovered = handler == null ? i -> {} : handler;
    }

    /** Reset the camera to fit-all plus the default isometric tilt. */
    public void resetView() {
        if (data != null) {
            camera.fitAll(data.ax, data.ay, data.az);
        }
        camera.reset();
        invalidateProjection();
        redraw();
    }

    private boolean classVisible(int classIndex) {
        return classIndex >= 0 && classIndex < visibleMask.length && visibleMask[classIndex];
    }

    // ---------------- level-of-detail (pure, testable) ----------------

    /** Per-frame LOD decision: whether thumbnails are active and their drawn size. The
     *  WHICH-cells question is answered by per-bucket occlusion, not a count cap. */
    static final class LodPlan {
        final boolean thumbnailsActive;
        final double thumbSizePx;

        LodPlan(boolean thumbnailsActive, double thumbSizePx) {
            this.thumbnailsActive = thumbnailsActive;
            this.thumbSizePx = thumbSizePx;
        }
    }

    /**
     * Decide the per-frame LOD. The on-screen footprint of a cell is
     * {@code baseScale * zoom * CELL_WORLD_SIZE} (orthographic: uniform across the
     * frame, driven by zoom). Thumbnails activate only when that footprint reaches
     * {@code minFootprintPx} AND the toggle is on; the drawn size is clamped to
     * {@code maxThumbPx}. WHICH cells draw as thumbnails is decided separately by
     * {@link #selectVisibleThumbnails} (screen-space overlap suppression), which
     * inherently bounds the load/draw count by visible screen area -- no count cap needed.
     */
    static LodPlan planLod(
            boolean showCellImages, double baseScale, double zoom, double minFootprintPx, double maxThumbPx) {
        double footprint = baseScale * zoom * CELL_WORLD_SIZE;
        boolean active = showCellImages && footprint >= minFootprintPx;
        double size = Math.max(minFootprintPx, Math.min(footprint, maxThumbPx));
        return new LodPlan(active, size);
    }

    /**
     * Screen-space occlusion selection by OVERLAP (PURE, unit-testable). Greedy
     * front-to-back non-max suppression: candidates are processed NEAREST-FIRST (the
     * front-most cell wins), and a candidate is accepted only if its (uniform-size,
     * orthographic) thumbnail rect overlaps EVERY already-accepted thumbnail by at most
     * {@code maxOverlapFrac} of its area. So visible thumbnails never overlap by more
     * than that fraction; a denser cell behind an accepted one stays a point and its crop
     * is never loaded. Off-screen cells (outside canvas + margin) are excluded.
     *
     * <p>Overlap fraction of two equal squares of side {@code s} with center offset
     * {@code (dx, dy)}: {@code max(0, s-|dx|) * max(0, s-|dy|) / (s*s)}. A candidate is
     * SUPPRESSED when that fraction is strictly {@code >} {@code maxOverlapFrac} (so
     * exactly-at-threshold is accepted).</p>
     *
     * <p>Cost is kept ~O(accepted) by hashing accepted centers into a screen-space grid
     * of side {@code s}: any accepted thumbnail that could overlap a candidate is within
     * {@code s} of its center, i.e. in the candidate's cell or its 8 neighbors.</p>
     *
     * @param drawOrder visible cell indices, sorted BACK-to-front (far first); iterated in
     *                  REVERSE here so the front-most cell is processed first
     * @param count     number of valid entries in {@code drawOrder}
     * @return the set of selected (visible, non-occluded) cell indices to draw as thumbnails
     */
    static java.util.Set<Integer> selectVisibleThumbnails(
            int[] drawOrder,
            int count,
            double[] projX,
            double[] projY,
            double[] projDepth,
            double thumbSize,
            double w,
            double h,
            double margin,
            double maxOverlapFrac) {
        double s = thumbSize > 0 ? thumbSize : 1.0;
        double area = s * s;
        java.util.Set<Integer> accepted = new java.util.HashSet<>();
        // Grid of accepted centers (cell side s): key -> list of accepted cell indices.
        java.util.Map<Long, java.util.List<Integer>> grid = new java.util.HashMap<>();

        // Nearest-first: drawOrder is far-to-near, so iterate in reverse.
        for (int k = count - 1; k >= 0; k--) {
            int i = drawOrder[k];
            double cxp = projX[i];
            double cyp = projY[i];
            if (!isOnScreenCandidate(cxp, cyp, w, h, margin)) {
                continue;
            }
            int gx = (int) Math.floor(cxp / s);
            int gy = (int) Math.floor(cyp / s);
            boolean occluded = false;
            for (int ax = gx - 1; ax <= gx + 1 && !occluded; ax++) {
                for (int ay = gy - 1; ay <= gy + 1 && !occluded; ay++) {
                    java.util.List<Integer> bucket = grid.get(cellKey(ax, ay));
                    if (bucket == null) {
                        continue;
                    }
                    for (int j : bucket) {
                        double dx = Math.abs(cxp - projX[j]);
                        double dy = Math.abs(cyp - projY[j]);
                        double ow = Math.max(0, s - dx);
                        double oh = Math.max(0, s - dy);
                        if ((ow * oh) / area > maxOverlapFrac) {
                            occluded = true;
                            break;
                        }
                    }
                }
            }
            if (!occluded) {
                accepted.add(i);
                grid.computeIfAbsent(cellKey(gx, gy), key -> new java.util.ArrayList<>())
                        .add(i);
            }
        }
        return accepted;
    }

    private static long cellKey(int cx, int cy) {
        return (((long) cx) << 32) ^ (cy & 0xffffffffL);
    }

    /**
     * Thumbnail eligibility (PURE): a per-cluster representative is a thumbnail candidate at
     * ANY zoom, so it passes even when the footprint gate is closed; a non-representative is a
     * candidate only when the footprint gate is open ({@code thumbnailsActive}).
     */
    static boolean isThumbnailCandidate(boolean thumbnailsActive, boolean isRepresentative) {
        return thumbnailsActive || isRepresentative;
    }

    /**
     * Viewport-cull predicate: true if a projected point at ({@code sx},{@code sy}) is
     * within the canvas ({@code w} x {@code h}) expanded by {@code margin} px, so a
     * partly-visible thumbnail still counts. Pure and unit-testable.
     */
    static boolean isOnScreenCandidate(double sx, double sy, double w, double h, double margin) {
        return sx >= -margin && sx <= w + margin && sy >= -margin && sy <= h + margin;
    }

    // ---------------- draw ----------------

    private void redraw() {
        GraphicsContext gc = canvas.getGraphicsContext2D();
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        boolean dark = isDarkTheme();
        // Background: a user-chosen color if set, else the theme-aware default.
        Color bg =
                backgroundColor != null ? backgroundColor : (dark ? Color.rgb(40, 40, 43) : Color.rgb(250, 250, 250));
        // Foreground (text/tripod) derived from background luminance so it stays readable on any color.
        double bgLum = 0.299 * bg.getRed() + 0.587 * bg.getGreen() + 0.114 * bg.getBlue();
        Color fg = bgLum < 0.5 ? Color.rgb(220, 220, 220) : Color.rgb(40, 40, 40);
        gc.setFill(bg);
        gc.fillRect(0, 0, w, h);

        if (emptyMessage != null || data == null || data.size() == 0) {
            gc.setFill(Color.gray(0.5));
            gc.setFont(Font.font("System", 13));
            gc.setTextAlign(TextAlignment.CENTER);
            gc.fillText(emptyMessage == null ? "No data to display." : emptyMessage, w / 2, h / 2);
            return;
        }

        int n = data.size();
        // Re-project + re-sort ONLY when the projection is dirty (camera / data /
        // visibility change). A crop-load-completion repaint reuses the cached order,
        // so it never re-sorts -- the primitive int[]/long[] buffers below also avoid
        // the per-frame boxed-Integer[] + comparator garbage of the old path.
        if (projectionDirty || projX.length != n) {
            rebuildProjection(n);
            projectionDirty = false;
        }

        double minD = cachedMinDepth;
        double dRange = cachedDRange;

        // Level-of-detail: is the thumbnail path active this frame, and at what size?
        LodPlan lod = planLod(
                showCellImages && cropLoader != null,
                camera.getBaseScale(),
                camera.getZoom(),
                MIN_FOOTPRINT_PX,
                MAX_THUMB_PX);

        // The cell-images feature master switch. When off, no thumbnails at all.
        boolean feature = showCellImages && cropLoader != null;

        // Build the far-to-near candidate order fed to the occlusion pass: per-cluster
        // representatives are candidates at ANY zoom (bypass the footprint gate); all other
        // cells are candidates only when zoomed in (thumbnailsActive). Then the EXISTING
        // greedy nearest-first overlap suppression (<= MAX_THUMB_OVERLAP_FRAC, front-most
        // wins) trims occluded ones -- so far out you see ~K per cluster, minus any hidden
        // behind nearer clusters.
        java.util.Set<Integer> thumbSelected;
        if (feature) {
            if (candBuf.length < visM) {
                candBuf = new int[visM];
            }
            int cc = 0;
            for (int k = 0; k < visM; k++) {
                int i = drawOrder[k];
                if (isThumbnailCandidate(lod.thumbnailsActive, isRepCandidate(i))) {
                    candBuf[cc++] = i;
                }
            }
            thumbSelected = selectVisibleThumbnails(
                    candBuf,
                    cc,
                    projX,
                    projY,
                    projDepth,
                    lod.thumbSizePx,
                    w,
                    h,
                    ON_SCREEN_MARGIN_PX,
                    MAX_THUMB_OVERLAP_FRAC);
        } else {
            thumbSelected = java.util.Collections.emptySet();
        }
        // Selected-but-uncached cells to load this frame (enqueued after the loop, prioritized
        // by distance from the cursor so the inspected region fills first).
        java.util.List<Integer> pending = new java.util.ArrayList<>();

        for (int k = 0; k < visM; k++) {
            int i = drawOrder[k];
            double sx = projX[i];
            double sy = projY[i];

            Color base = data.palette[data.classIdx[i]];
            if (base == null) {
                base = Color.GRAY;
            }

            // Thumbnail path only for the selected (non-occluded, on-screen) cells.
            if (feature && thumbSelected.contains(i)) {
                Image img = imageCache.get(i);
                if (img != null) {
                    double s = lod.thumbSizePx;
                    gc.drawImage(img, sx - s / 2, sy - s / 2, s, s);
                    // Thin class-colored border so class stays readable.
                    gc.setStroke(base);
                    gc.setLineWidth(1.5);
                    gc.strokeRect(sx - s / 2, sy - s / 2, s, s);
                    continue;
                }
                // Not cached yet: mark for a prioritized load, and fall through to a point.
                pending.add(i);
            }

            double pr = pointSize;
            double alpha = 0.9;
            if (depthCue) {
                // Nearer (small depth) -> brighter + larger.
                double t = (projDepth[i] - minD) / dRange; // 0 near, 1 far
                double shade = 1.0 - 0.55 * t;
                base = base.deriveColor(0, 1.0, shade, 1.0);
                pr = pointSize * (1.0 - 0.4 * t);
                alpha = 0.95 - 0.35 * t;
            }
            gc.setFill(Color.color(base.getRed(), base.getGreen(), base.getBlue(), alpha));
            gc.fillOval(sx - pr, sy - pr, pr * 2, pr * 2);
        }

        // Enqueue pending crop loads nearest the cursor (or canvas center) first, up to the
        // per-frame + global in-flight caps.
        if (!pending.isEmpty()) {
            double focusX = Double.isNaN(lastCursorX) ? w / 2 : lastCursorX;
            double focusY = Double.isNaN(lastCursorY) ? h / 2 : lastCursorY;
            pending.sort((a, b) -> Double.compare(
                    sqDist(projX[a], projY[a], focusX, focusY), sqDist(projX[b], projY[b], focusX, focusY)));
            int loads = 0;
            for (int idx : pending) {
                if (loads >= MAX_LOADS_PER_FRAME || loadingIndices.size() >= MAX_IN_FLIGHT) {
                    break;
                }
                if (enqueueCrop(idx)) {
                    loads++;
                }
            }
        }
        // Populating banner: still loading while any selected cell is uncached OR loads are in flight.
        updatePopulating(!pending.isEmpty() || !loadingIndices.isEmpty());

        // Selection ring.
        if (selectedIndex >= 0 && selectedIndex < n && classVisible(data.classIdx[selectedIndex])) {
            gc.setStroke(fg);
            gc.setLineWidth(2);
            double sx = projX[selectedIndex];
            double sy = projY[selectedIndex];
            gc.strokeOval(sx - 7, sy - 7, 14, 14);
        }

        if (showTripod) {
            drawTripod(gc, w, h, fg);
        }
    }

    /**
     * Project every point and rebuild the depth-sorted visible draw order + pick grid.
     * Uses reused primitive buffers (no per-frame boxed {@code Integer[]} or comparator):
     * visible cells are packed into a {@code long} (inverted-depth key in the high 32 bits,
     * cell index in the low 32) and sorted with the primitive {@link java.util.Arrays#sort},
     * yielding a far-to-near draw order. Called only when the projection is dirty.
     */
    private void rebuildProjection(int n) {
        if (projX.length != n) {
            projX = new double[n];
            projY = new double[n];
            projDepth = new double[n];
        }
        if (drawOrder.length < n) {
            drawOrder = new int[n];
            sortBuf = new long[n];
        }

        for (int i = 0; i < n; i++) {
            double[] p = camera.project(data.ax[i], data.ay[i], data.az[i]);
            projX[i] = p[0];
            projY[i] = p[1];
            projDepth[i] = p[2];
        }

        // Pass 1: collect visible indices (temporarily in drawOrder) + depth range.
        int m = 0;
        double minD = Double.MAX_VALUE;
        double maxD = -Double.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            if (classVisible(data.classIdx[i])) {
                double d = projDepth[i];
                if (d < minD) {
                    minD = d;
                }
                if (d > maxD) {
                    maxD = d;
                }
                drawOrder[m++] = i;
            }
        }
        double dRange = (maxD - minD) <= 0 ? 1.0 : (maxD - minD);

        // Pass 2: pack an inverted-depth sort key so ascending sort = far-to-near.
        for (int k = 0; k < m; k++) {
            int i = drawOrder[k];
            long depthKey = (long) ((maxD - projDepth[i]) / dRange * DEPTH_SORT_SCALE);
            if (depthKey < 0) {
                depthKey = 0;
            }
            sortBuf[k] = (depthKey << 32) | (i & 0xffffffffL);
        }
        java.util.Arrays.sort(sortBuf, 0, m);
        for (int k = 0; k < m; k++) {
            drawOrder[k] = (int) (sortBuf[k] & 0xffffffffL);
        }

        // Rebuild the pick grid from the projected coords (valid until the next reproject).
        pickGrid = new PickGrid(PICK_RADIUS);
        for (int k = 0; k < m; k++) {
            int i = drawOrder[k];
            pickGrid.add(i, projX[i], projY[i], projDepth[i]);
        }

        visM = m;
        cachedMinDepth = minD;
        cachedDRange = dRange;
    }

    /**
     * Enqueue an off-thread crop load for a cell whose thumbnail is not cached.
     * Deduplicates in-flight loads. Returns true if a load was actually submitted.
     * Must be called on the FX thread (mutates the loading set + cache on FX only).
     */
    private boolean enqueueCrop(int index) {
        // The window may have closed (shutdown()) while a crop-completion redraw was
        // queued on the FX thread; do not submit to the terminated pool.
        if (disposed) {
            return false;
        }
        if (cropLoader == null || data == null || index < 0 || index >= data.refs.length) {
            return false;
        }
        if (imageCache.containsKey(index) || loadingIndices.contains(index)) {
            return false;
        }
        CellRef ref = data.refs[index];
        if (ref == null) {
            return false;
        }
        final int gen = imageCacheGen;
        final double scale = cropScaleSupplier.getAsDouble();
        loadingIndices.add(index);
        try {
            cropExecutor.submit(() -> {
                BufferedImage crop;
                try {
                    crop = cropLoader.load(ref, scale);
                } catch (Exception e) {
                    crop = null;
                }
                final Image img = crop != null ? SwingFXUtils.toFXImage(crop, null) : null;
                Platform.runLater(() -> {
                    // Discard if the cache/dataset changed while we were loading.
                    if (gen != imageCacheGen) {
                        return;
                    }
                    loadingIndices.remove(index);
                    if (img != null) {
                        imageCache.put(index, img);
                        // Coalesce: a burst of completions triggers ONE redraw, not one
                        // per crop (which would cascade into a redraw storm that enqueues
                        // more crops each frame and freezes the FX thread).
                        scheduleRedraw();
                    }
                });
            });
        } catch (java.util.concurrent.RejectedExecutionException ex) {
            // shutdown() raced between the disposed check and submit; undo + bail quietly.
            loadingIndices.remove(index);
            return false;
        }
        return true;
    }

    private static double sqDist(double ax, double ay, double bx, double by) {
        double dx = ax - bx;
        double dy = ay - by;
        return dx * dx + dy * dy;
    }

    /** Update the debounced populating signal: true fires immediately; false is delayed ~300ms. */
    private void updatePopulating(boolean loadingNow) {
        if (loadingNow) {
            populatingHideDelay.stop();
            populating.set(true);
        } else if (populating.get()) {
            populatingHideDelay.playFromStart(); // hide after the idle delay (debounce)
        }
    }

    /** Schedule at most one redraw for a burst of crop-load completions (FX thread only). */
    private void scheduleRedraw() {
        if (redrawScheduled) {
            return;
        }
        redrawScheduled = true;
        Platform.runLater(() -> {
            redrawScheduled = false;
            redraw();
        });
    }

    private void drawTripod(GraphicsContext gc, double w, double h, Color fg) {
        double ox = 44;
        double oy = h - 44;
        double len = 30;
        // Project three unit axis directions relative to the cloud center by
        // projecting short segments from the camera origin. We reuse the camera's
        // rotation by projecting axis endpoints in normalized cloud space.
        double[] o = camera.project(0, 0, 0);
        double[] px = camera.project(0.3, 0, 0);
        double[] py = camera.project(0, 0.3, 0);
        double[] pz = camera.project(0, 0, 0.3);
        drawAxis(gc, ox, oy, o, px, len, Color.rgb(220, 80, 80), "X");
        drawAxis(gc, ox, oy, o, py, len, Color.rgb(80, 200, 80), "Y");
        // In flat 2D the Z axis has no screen direction -- omit it.
        if (!twoD) {
            drawAxis(gc, ox, oy, o, pz, len, Color.rgb(90, 140, 230), "Z");
        }
    }

    private void drawAxis(
            GraphicsContext gc, double ox, double oy, double[] o, double[] tip, double len, Color c, String label) {
        double dx = tip[0] - o[0];
        double dy = tip[1] - o[1];
        double mag = Math.hypot(dx, dy);
        if (mag < 1e-6) {
            mag = 1;
        }
        double ex = ox + dx / mag * len;
        double ey = oy + dy / mag * len;
        gc.setStroke(c);
        gc.setLineWidth(2);
        gc.strokeLine(ox, oy, ex, ey);
        gc.setFill(c);
        gc.setFont(Font.font("System", 10));
        gc.setTextAlign(TextAlignment.CENTER);
        gc.fillText(label, ex, ey - 3);
    }

    // ---------------- gestures ----------------

    private void onScroll(ScrollEvent e) {
        if (data == null) {
            return;
        }
        double factor = e.getDeltaY() > 0 ? 1.18 : 0.85;
        camera.zoomAt(factor, e.getX(), e.getY());
        invalidateProjection();
        redraw();
        e.consume();
    }

    private void onMousePressed(MouseEvent e) {
        dragStartX = e.getX();
        dragStartY = e.getY();
        lastDragX = e.getX();
        lastDragY = e.getY();
        dragTotal = 0;
        dragButton = e.getButton();
        canvas.requestFocus();
    }

    private void onMouseDragged(MouseEvent e) {
        if (data == null) {
            return;
        }
        double dx = e.getX() - lastDragX;
        double dy = e.getY() - lastDragY;
        dragTotal += Math.abs(dx) + Math.abs(dy);
        lastDragX = e.getX();
        lastDragY = e.getY();

        // Pan on middle-drag OR Shift+left-drag (trackpad / no-middle-button fallback).
        // In flat 2D there is nothing to rotate, so a plain left-drag pans too.
        boolean panGesture = dragButton == MouseButton.MIDDLE
                || (dragButton == MouseButton.PRIMARY && e.isShiftDown())
                || (dragButton == MouseButton.PRIMARY && twoD);
        if (panGesture) {
            camera.pan(dx, dy);
            invalidateProjection();
            redraw();
        } else if (dragButton == MouseButton.PRIMARY) {
            camera.rotate(dx * ROTATE_SPEED, dy * ROTATE_SPEED);
            invalidateProjection();
            redraw();
        }
    }

    private void onMouseReleased(MouseEvent e) {
        if (data == null) {
            return;
        }
        // A Shift+left-drag is a pan, never a select click.
        boolean wasClick = dragButton == MouseButton.PRIMARY
                && !e.isShiftDown()
                && Math.hypot(e.getX() - dragStartX, e.getY() - dragStartY) <= CLICK_THRESHOLD_PX;
        if (wasClick) {
            handleClick(e.getX(), e.getY());
        }
        dragButton = null;
    }

    private void handleClick(double px, double py) {
        if (pickGrid == null || data == null) {
            return;
        }
        int idx = pickGrid.pick(px, py, PICK_RADIUS);
        if (idx < 0 || idx >= data.refs.length) {
            return;
        }
        CellRef ref = data.refs[idx];
        if (ref == null) {
            return;
        }
        selectedIndex = idx;
        redraw();
        ViewerNavigator.navigateToCell(qupath, ref.getImageId(), ref.getImageName(), ref.getX(), ref.getY());
        onPointClicked.accept(idx);
    }

    private void onMouseMoved(MouseEvent e) {
        // Track the cursor so crop loads can be prioritized toward what the user inspects.
        lastCursorX = e.getX();
        lastCursorY = e.getY();
        if (data == null || pickGrid == null) {
            return;
        }
        double dx = e.getX() - lastHoverX;
        double dy = e.getY() - lastHoverY;
        if (dx * dx + dy * dy < HOVER_MOVE_GUARD_SQ) {
            return;
        }
        lastHoverX = e.getX();
        lastHoverY = e.getY();

        int idx = pickGrid.pick(e.getX(), e.getY(), PICK_RADIUS);
        if (idx == lastHoverIdx) {
            return;
        }
        lastHoverIdx = idx;
        if (idx >= 0) {
            // Show the RAW measurement values against the real axis names (the
            // normalized render coords are internal to drawing only). In 2D the Z
            // axis is unused, so omit its line.
            String text = twoD
                    ? String.format(
                            "%s%n%s %.3g%n%s %.3g",
                            data.classDisplayName(data.classIdx[idx]),
                            data.xName,
                            data.rawX[idx],
                            data.yName,
                            data.rawY[idx])
                    : String.format(
                            "%s%n%s %.3g%n%s %.3g%n%s %.3g",
                            data.classDisplayName(data.classIdx[idx]),
                            data.xName,
                            data.rawX[idx],
                            data.yName,
                            data.rawY[idx],
                            data.zName,
                            data.rawZ[idx]);
            tooltip.setText(text);
            if (hoverPreviewEnabled) {
                onPointHovered.accept(idx);
            }
        } else {
            tooltip.setText(gestureHint());
        }
    }

    /** Gesture cheat-sheet shown on the Canvas tooltip when not over a point (3D). */
    static final String GESTURE_HINT = "Left-drag to rotate, scroll to zoom, middle-drag or Shift+drag to pan. "
            + "Click a point to center and select that cell.";

    /** Gesture cheat-sheet for the flat 2D view (no rotation). */
    static final String GESTURE_HINT_2D = "Left-drag or middle-drag to pan, scroll to zoom. "
            + "Click a point to center and select that cell.";

    private String gestureHint() {
        return twoD ? GESTURE_HINT_2D : GESTURE_HINT;
    }

    private String accessibilityText() {
        return twoD
                ? "Flat 2D scatter of clustered cells. Left-drag or middle-drag to pan, scroll to zoom, "
                        + "click a point to select and center that cell. Use the Reset view button to fit all points."
                : "3D point cloud of clustered cells. Left-drag to rotate, scroll to zoom, middle-drag or "
                        + "Shift+drag to pan, click a point to select and center that cell. Use the Reset view "
                        + "button to return to the default view.";
    }

    /** Best-effort dark-theme detection from the applied stylesheets (name-based). */
    private boolean isDarkTheme() {
        return ThemeUtils.isDark(canvas.getScene());
    }
}
