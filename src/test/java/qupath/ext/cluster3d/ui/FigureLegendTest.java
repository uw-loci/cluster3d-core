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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Label;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** A saved figure must carry every class, wrapped so a long list stays a sensible shape. */
class FigureLegendTest {

    private static final double PLOT_W = 300;
    private static final double PLOT_H = 343;

    private static boolean fxUp;

    @BeforeAll
    static void startFx() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        try {
            Platform.startup(latch::countDown);
        } catch (IllegalStateException alreadyRunning) {
            latch.countDown();
        } catch (UnsupportedOperationException | Error noDisplay) {
            return;
        }
        fxUp = latch.await(60, TimeUnit.SECONDS);
        Platform.setImplicitExit(false);
    }

    private static <T> T onFx(Callable<T> work) throws Exception {
        CompletableFuture<T> out = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                out.complete(work.call());
            } catch (Throwable t) {
                out.completeExceptionally(t);
            }
        });
        return out.get(30, TimeUnit.SECONDS);
    }

    private static List<FigureLegend.Entry> entries(int n) {
        List<FigureLegend.Entry> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(new FigureLegend.Entry(Color.CORNFLOWERBLUE, "Cluster " + i, 100 + i));
        }
        return out;
    }

    private static Node figure(int n) {
        return FigureLegend.figure(new WritableImage(300, 343), PLOT_W, PLOT_H, "Classes (" + n + ")", entries(n));
    }

    @Test
    void columnsHoldWhatFitsBesideThePlot() {
        assertEquals(20, FigureLegend.rowsPerColumn(PLOT_H));
        assertEquals(1, FigureLegend.rowsPerColumn(0));
    }

    @Test
    void sixtyClassesAllAppearNoTallerThanThePlot() throws Exception {
        Assumptions.assumeTrue(fxUp, "no display");
        double[] height = new double[1];
        long names = onFx(() -> {
            Node figure = figure(60);
            height[0] = figure.getBoundsInLocal().getHeight();
            return figure.lookupAll(".label").stream()
                    .filter(n -> ((Label) n).getText().startsWith("Cluster "))
                    .count();
        });
        assertEquals(60, names);
        assertTrue(height[0] <= PLOT_H + 40, "figure height " + height[0]);
    }

    /** Controls drew nothing without a scene, and row counts did not notice. */
    @Test
    void textReachesTheImageInAnyTheme() throws Exception {
        Assumptions.assumeTrue(fxUp, "no display");
        String whiteLabels = "data:text/css;base64,"
                + Base64.getEncoder()
                        .encodeToString(".label { -fx-text-fill: white; }".getBytes(StandardCharsets.UTF_8));
        WritableImage image = onFx(() -> {
            Node figure = figure(6);
            figure.getScene().getStylesheets().add(whiteLabels);
            figure.applyCss();
            return figure.snapshot(new SnapshotParameters(), null);
        });
        PixelReader reader = image.getPixelReader();
        int dark = 0;
        for (int y = 0; y < (int) image.getHeight(); y++) {
            for (int x = (int) PLOT_W + 20; x < (int) image.getWidth(); x++) {
                if (reader.getColor(x, y).getBrightness() < 0.3) {
                    dark++;
                }
            }
        }
        assertTrue(dark > 100, "dark legend pixels: " + dark);
    }
}
