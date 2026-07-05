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
 * Adapted from qupath.ext.qpcat.service.CellCropService in QP-CAT
 * (qupath-extension-cell-analysis-tools), licensed Apache-2.0.
 */

package qupath.ext.cluster3d.service;

import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.cluster3d.model.CellRef;
import qupath.lib.display.ChannelDisplayInfo;
import qupath.lib.display.ImageDisplay;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.regions.RegionRequest;

/**
 * Reads small image crops centered on a cell, sized to a multiple of the cell's
 * bounding box. Backs the hover / click preview thumbnail.
 *
 * <p>Crops are read through an {@link ImageServer} + {@link RegionRequest}, which
 * is lazy/tile-based -- it does NOT pull the whole image into memory the way
 * opening it in the viewer does. Servers built from project entries are held in a
 * small bounded LRU (at most {@value #MAX_OPEN_SERVERS} open at once; the
 * least-recently-used is closed on eviction) so repeated crops from a recent image
 * don't re-open the file, without holding one open server per image for the whole
 * session on a many-image cloud. The already-open viewer image is reused directly
 * and is never placed in (or closed by) that cache.</p>
 *
 * <p>{@link #readCrop} touches disk and MUST be called off the JavaFX application
 * thread; only push the resulting image back to FX. Call {@link #close()} when the
 * owning window closes to release cached servers (the open viewer's own server is
 * never closed here).</p>
 */
public class CellCropService implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(CellCropService.class);

    /** Default crop side as a multiple of the cell's larger bounding-box side. */
    public static final double DEFAULT_CROP_SCALE = 3.0;
    /** Fallback crop half-extent (px) when a cell's bounding box is unknown. */
    private static final double FALLBACK_HALF_PX = 40.0;
    /** Target crop output size (px) -- the downsample is chosen to land near this. */
    private static final int TARGET_OUTPUT_PX = 224;
    /** Max number of self-built ImageServers kept open at once (bounded LRU). */
    private static final int MAX_OPEN_SERVERS = 8;

    private final QuPathGUI qupath;
    // Bounded LRU of servers we built ourselves (NOT the open viewer's server). Kept
    // small so a many-image cloud does not hold one open (tile-caching) server per image
    // for the whole session; the least-recently-used server is closed on eviction. This
    // map is NOT thread-safe (access-ordered LinkedHashMap), so EVERY access -- read,
    // build, evict, close -- is guarded by serversLock (crops are read off multiple
    // loader threads).
    private final Object serversLock = new Object();
    private final LinkedHashMap<String, ImageServer<BufferedImage>> builtServers =
            boundedLru(MAX_OPEN_SERVERS, CellCropService::closeServerQuietly);
    // Cache of default displays for non-current images (the current image uses
    // the live viewer display so edits show up on refresh).
    private final Map<String, ImageDisplay> displays = new ConcurrentHashMap<>();

    public CellCropService(QuPathGUI qupath) {
        this.qupath = qupath;
    }

    /**
     * Read a crop centered on the given cell. The crop is a square whose side is
     * {@code cropScale * max(bboxWidth, bboxHeight)} (falling back to a fixed size
     * when the bounding box is unknown), clamped to image bounds, read at a
     * downsample chosen so the returned image is roughly {@link #TARGET_OUTPUT_PX}
     * on its long side.
     *
     * @return the crop, or {@code null} if no server could be resolved / read failed
     */
    public BufferedImage readCrop(CellRef ref, double cropScale) {
        if (ref == null) {
            return null;
        }
        ImageServer<BufferedImage> server = resolveServer(ref);
        if (server == null) {
            logger.warn("No image server for crop (imageId={}, name={})", ref.getImageId(), ref.getImageName());
            return null;
        }

        CropWindow w = computeCropWindow(
                server.getWidth(), server.getHeight(), ref.getX(), ref.getY(), ref.getBboxHalf(), cropScale);
        try {
            RegionRequest request =
                    RegionRequest.createInstance(server.getPath(), w.downsample, w.x, w.y, w.side, w.side);
            return applyDisplay(ref, server, server.readRegion(request));
        } catch (Exception e) {
            logger.warn(
                    "Crop read failed at ({}, {}) side={} ds={}: {}", w.x, w.y, w.side, w.downsample, e.getMessage());
            return null;
        }
    }

    /** Pure crop-window geometry, extracted so it can be unit-tested without a server. */
    public static CropWindow computeCropWindow(
            int imgW, int imgH, double cx, double cy, double bboxHalf, double cropScale) {
        double half = bboxHalf > 0 ? bboxHalf * cropScale : FALLBACK_HALF_PX * cropScale;
        int side = (int) Math.round(half * 2);
        side = Math.max(8, Math.min(side, Math.min(imgW, imgH)));

        // Top-left, clamped so the full side fits inside the image.
        int x = (int) Math.round(cx - side / 2.0);
        int y = (int) Math.round(cy - side / 2.0);
        x = Math.max(0, Math.min(x, imgW - side));
        y = Math.max(0, Math.min(y, imgH - side));

        // Pick a downsample so the crop renders near TARGET_OUTPUT_PX on its long side.
        double downsample = Math.max(1.0, (double) side / TARGET_OUTPUT_PX);
        return new CropWindow(x, y, side, downsample);
    }

    /** Immutable crop-window result: clamped top-left, square side, read downsample. */
    public static final class CropWindow {
        public final int x;
        public final int y;
        public final int side;
        public final double downsample;

        public CropWindow(int x, int y, int side, double downsample) {
            this.x = x;
            this.y = y;
            this.side = side;
            this.downsample = downsample;
        }
    }

    /** Convenience overload using {@link #DEFAULT_CROP_SCALE}. */
    public BufferedImage readCrop(CellRef ref) {
        return readCrop(ref, DEFAULT_CROP_SCALE);
    }

    /**
     * Colorize a raw region with the image's display settings (brightness /
     * contrast / channel selection + colors) so multichannel / fluorescence crops
     * match what the user sees in the viewer, instead of a raw, washed-out pixel
     * dump. For the open viewer image this uses the LIVE viewer display. Returns
     * the raw image unchanged if no display is available or the transform fails.
     */
    private BufferedImage applyDisplay(CellRef ref, ImageServer<BufferedImage> server, BufferedImage raw) {
        if (raw == null) {
            return null;
        }
        ImageDisplay display = resolveDisplay(ref, server);
        if (display == null) {
            return raw;
        }
        try {
            return ImageDisplay.applyTransforms(
                    raw, null, display.selectedChannels(), display.displayMode().getValue());
        } catch (Exception e) {
            logger.warn("Display transform failed; using raw crop: {}", e.getMessage());
            return raw;
        }
    }

    private ImageDisplay resolveDisplay(CellRef ref, ImageServer<BufferedImage> server) {
        // Current viewer image -> the LIVE viewer display, so brightness/contrast/
        // channel edits are reflected when the preview is refreshed.
        var viewer = qupath.getViewer();
        ImageData<BufferedImage> currentData = qupath.getImageData();
        if (viewer != null
                && currentData != null
                && currentData.getServer() == server
                && viewer.getImageDisplay() != null) {
            return viewer.getImageDisplay();
        }
        // Other images -> a cached display that MIRRORS the open viewer's channel
        // selection + contrast (multiplex panels share channel names), so "Update from
        // viewer" affects crops from every image, not just the currently-open one.
        // The cache is cleared on "Update from viewer", so this re-mirrors each refresh.
        if (ref.getImageId() == null) {
            return null;
        }
        ImageDisplay viewerDisplay = (viewer != null) ? viewer.getImageDisplay() : null;
        return displays.computeIfAbsent(ref.getImageId(), id -> {
            try {
                ImageDisplay d = ImageDisplay.create(new ImageData<>(server));
                mirrorViewerDisplay(d, viewerDisplay, ref);
                return d;
            } catch (Exception e) {
                logger.warn("Could not build display for '{}': {}", ref.getImageName(), e.getMessage());
                return null;
            }
        });
    }

    /**
     * Copy the open viewer's display onto another image's display so a crop from that
     * image matches what the user sees. Uses QuPath's cross-image {@code updateFromDisplay}
     * (matches channels by name), then explicitly mirrors the on/off selection by channel
     * name -- guarded so a panel whose channel names do not overlap is left at its defaults
     * (never blanked).
     */
    private void mirrorViewerDisplay(ImageDisplay target, ImageDisplay source, CellRef ref) {
        if (target == null || source == null) {
            return;
        }
        try {
            target.updateFromDisplay(source);
        } catch (Exception e) {
            logger.debug("updateFromDisplay failed for '{}': {}", ref.getImageName(), e.getMessage());
        }
        try {
            java.util.Set<String> onNames = new java.util.HashSet<>();
            for (ChannelDisplayInfo ch : source.selectedChannels()) {
                onNames.add(ch.getName());
            }
            boolean anyMatch = false;
            for (ChannelDisplayInfo ch : target.availableChannels()) {
                if (onNames.contains(ch.getName())) {
                    anyMatch = true;
                    break;
                }
            }
            if (anyMatch) {
                for (ChannelDisplayInfo ch : target.availableChannels()) {
                    target.setChannelSelected(ch, onNames.contains(ch.getName()));
                }
            }
        } catch (Exception e) {
            logger.debug("Could not mirror channel selection for '{}': {}", ref.getImageName(), e.getMessage());
        }
    }

    /**
     * Resolve (and cache) an ImageServer for the cell's source image. Reuses the
     * open viewer's server when the cell belongs to the current image.
     */
    private ImageServer<BufferedImage> resolveServer(CellRef ref) {
        // 1. Already-open image -- reuse its server (do not cache/close it).
        ImageData<BufferedImage> currentData = qupath.getImageData();
        if (currentData != null) {
            String currentName = currentData.getServer().getMetadata().getName();
            Project<BufferedImage> project = qupath.getProject();
            String currentId = null;
            if (project != null) {
                ProjectImageEntry<BufferedImage> e = project.getEntry(currentData);
                if (e != null) {
                    currentId = e.getID();
                }
            }
            boolean matchesId = ref.getImageId() != null && ref.getImageId().equals(currentId);
            boolean matchesName =
                    ref.getImageName() != null && ref.getImageName().equals(currentName);
            if (matchesId || matchesName) {
                return currentData.getServer();
            }
        }

        // 2. Bounded LRU of self-built servers (built once per source image, evicted +
        //    closed when > MAX_OPEN_SERVERS distinct images are in play). All map access
        //    is under serversLock; the build happens under the lock too, which serializes
        //    concurrent first-time builds of the SAME image (avoids a duplicate-build leak)
        //    at the cost of briefly blocking the other loader thread -- acceptable given
        //    only ~2 loader threads and infrequent builds.
        //
        //    Residual close-while-reading risk: the eldest (LRU) server is closed on
        //    eviction. A server currently being read was just returned by resolveServer,
        //    which marks it MOST-recently-used, so it is the LAST to be evicted -- it would
        //    take MAX_OPEN_SERVERS other distinct-image reads to evict it. With quick,
        //    tile-based reads this effectively never closes an in-flight server; the window
        //    is tiny and, if ever hit, surfaces as a single failed crop (logged), not a crash.
        if (ref.getImageId() == null) {
            return null;
        }
        synchronized (serversLock) {
            ImageServer<BufferedImage> cached = builtServers.get(ref.getImageId()); // marks MRU
            if (cached != null) {
                return cached;
            }
            ImageServer<BufferedImage> built = buildServer(ref.getImageId());
            if (built != null) {
                builtServers.put(ref.getImageId(), built); // may evict + close the eldest
            }
            return built;
        }
    }

    /** Build a fresh ImageServer for the given project-entry id, or null on failure. */
    private ImageServer<BufferedImage> buildServer(String id) {
        Project<BufferedImage> project = qupath.getProject();
        if (project == null) {
            return null;
        }
        for (ProjectImageEntry<BufferedImage> entry : project.getImageList()) {
            if (id.equals(entry.getID())) {
                try {
                    return entry.getServerBuilder().build();
                } catch (Exception e) {
                    logger.warn("Failed to build server for '{}': {}", entry.getImageName(), e.getMessage());
                    return null;
                }
            }
        }
        return null;
    }

    /**
     * Access-ordered LRU map that invokes {@code onEvict} on (and then removes) the
     * eldest entry once {@code size() > maxSize}. Generic + pure so the eviction policy
     * is unit-testable without real ImageServers. NOT thread-safe -- callers guard access.
     */
    static <V> LinkedHashMap<String, V> boundedLru(int maxSize, Consumer<V> onEvict) {
        return new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, V> eldest) {
                if (size() > maxSize) {
                    onEvict.accept(eldest.getValue());
                    return true;
                }
                return false;
            }
        };
    }

    private static void closeServerQuietly(ImageServer<BufferedImage> server) {
        if (server == null) {
            return;
        }
        try {
            server.close();
        } catch (Exception e) {
            logger.debug("Error closing cached server: {}", e.getMessage());
        }
    }

    /**
     * Drop cached default displays for non-current images so the next crop re-reads
     * their display settings. Leaves {@link #builtServers} intact (we only want to
     * discard cached DISPLAYS, not re-open servers). No-op for the current viewer
     * image, which always uses the live viewer display. Call this before refreshing
     * a preview after the user changed channel visibility or brightness/contrast.
     */
    public void clearDisplayCache() {
        displays.clear();
    }

    @Override
    public void close() {
        synchronized (serversLock) {
            for (ImageServer<BufferedImage> server : builtServers.values()) {
                closeServerQuietly(server);
            }
            builtServers.clear();
        }
        displays.clear();
    }
}
