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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.TextField;
import javafx.scene.paint.Color;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import qupath.ext.cluster3d.model.PointCloudData;
import qupath.lib.objects.classes.PathClass;

/** With dozens of classes the list needs narrowing, and All / None must follow it. */
class ClassLegendFilterTest {

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

    private static PointCloudData data(int n) {
        PathClass[] classes = new PathClass[n];
        Color[] palette = new Color[n];
        int[] counts = new int[n];
        for (int i = 0; i < n; i++) {
            classes[i] = PathClass.fromString((i % 2 == 0 ? "CD8 T " : "Tumor ") + i);
            palette[i] = Color.CORNFLOWERBLUE;
            counts[i] = 10;
        }
        return new PointCloudData(
                new float[0],
                new float[0],
                new float[0],
                new double[0],
                new double[0],
                new double[0],
                new int[0],
                List.of(classes),
                palette,
                counts,
                null,
                "x",
                "y",
                "z",
                0);
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

    private static TextField filterOf(ClassLegend legend) {
        return (TextField) legend.lookup(".text-field");
    }

    private static void press(ClassLegend legend, String link) {
        for (Node node : new ArrayList<>(legend.lookupAll(".hyperlink"))) {
            if (((Hyperlink) node).getText().equals(link)) {
                ((Hyperlink) node).fireEvent(new ActionEvent());
            }
        }
    }

    @Test
    void matchingIgnoresCaseAndOuterSpaces() {
        assertTrue(ClassLegend.matches("CD8 T cell", " cd8 "));
        assertTrue(ClassLegend.matches("CD8 T cell", ""));
        assertTrue(ClassLegend.matches("CD8 T cell", null));
        assertFalse(ClassLegend.matches("CD8 T cell", "tumor"));
    }

    @Test
    void classesAreListedByNameWithNumbersInOrder() {
        String[] names = {"Cluster 10", "Tumor", "Cluster 2", "cd8", "Cluster 02b", ""};
        int[] order = ClassLegend.displayOrder(names);
        assertArrayEquals(new int[] {5, 3, 2, 4, 0, 1}, order);
    }

    @Test
    void theFilterAppearsOnlyAboveTheThreshold() throws Exception {
        Assumptions.assumeTrue(fxUp, "no display");
        assertFalse(onFx(() -> {
            ClassLegend legend = new ClassLegend();
            legend.setData(data(ClassLegend.FILTER_MIN_CLASSES));
            return filterOf(legend).isVisible();
        }));
        assertTrue(onFx(() -> {
            ClassLegend legend = new ClassLegend();
            legend.setData(data(ClassLegend.FILTER_MIN_CLASSES + 1));
            return filterOf(legend).isVisible();
        }));
    }

    @Test
    void noneActsOnlyOnTheClassesListed() throws Exception {
        Assumptions.assumeTrue(fxUp, "no display");
        boolean[] mask = onFx(() -> {
            ClassLegend legend = new ClassLegend();
            legend.setData(data(20));
            filterOf(legend).setText("cd8");
            press(legend, "None");
            return legend.getVisibleMask().clone();
        });
        for (int i = 0; i < mask.length; i++) {
            assertEquals(i % 2 != 0, mask[i], "class " + i);
        }
    }

    @Test
    void aReloadKeepsTheFilterApplied() throws Exception {
        Assumptions.assumeTrue(fxUp, "no display");
        long managed = onFx(() -> {
            ClassLegend legend = new ClassLegend();
            legend.setData(data(20));
            filterOf(legend).setText("tumor");
            legend.setData(data(20));
            new Scene(legend, 260, 600);
            legend.applyCss();
            legend.layout();
            return legend.lookupAll(".check-box").stream()
                    .filter(box -> box.getParent().isManaged())
                    .count();
        });
        assertEquals(10, managed);
    }
}
