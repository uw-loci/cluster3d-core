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

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import qupath.ext.cluster3d.model.PointCloudData;

/**
 * Right-panel class legend: one row per class (checkbox + color swatch + name +
 * count) plus All / None controls. Toggling a checkbox updates the shared
 * visibility mask and fires the change callback, which repaints the cloud and
 * refreshes the "shown / total" counter.
 */
public class ClassLegend extends VBox {

    private final VBox rows = new VBox(2);
    private final Label header = new Label("CLASSES");
    private boolean[] visible = new boolean[0];
    private Runnable onChange = () -> {};
    // Class name -> whether it was shown, carried across a reload. Changing an
    // axis measurement re-reads the detections and rebuilds this legend, and
    // rebuilding used to turn every class back on -- so narrowing to two classes
    // and then looking at them against a different measurement, which is the
    // normal way to use this, silently put all twenty back.
    private final java.util.Map<String, Boolean> rememberedVisibility =
            new java.util.HashMap<>();

    public ClassLegend() {
        setSpacing(6);
        setPadding(new Insets(6));
        header.setStyle("-fx-font-weight: bold;");

        Hyperlink all = new Hyperlink("All");
        all.setTooltip(new javafx.scene.control.Tooltip("Show all classes."));
        all.setOnAction(e -> setAll(true));
        Hyperlink none = new Hyperlink("None");
        none.setTooltip(new javafx.scene.control.Tooltip("Hide all classes."));
        none.setOnAction(e -> setAll(false));
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox controls = new HBox(4, header, spacer, all, none);
        controls.setAlignment(Pos.CENTER_LEFT);

        ScrollPane scroll = new ScrollPane(rows);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportHeight(320);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        getChildren().addAll(controls, scroll);
    }

    /** Register the callback fired whenever the visibility mask changes. */
    public void setOnChange(Runnable onChange) {
        this.onChange = onChange == null ? () -> {} : onChange;
    }

    /**
     * Rebuild the rows for a new dataset, keeping each class's shown / hidden
     * state from the previous dataset. State is remembered by class NAME, not by
     * index, so it survives a class set that gains, loses or reorders entries; a
     * class never seen before starts visible.
     */
    public void setData(PointCloudData data) {
        rows.getChildren().clear();
        if (data == null || data.classCount() == 0) {
            visible = new boolean[0];
            return;
        }
        int n = data.classCount();
        String[] names = new String[n];
        for (int i = 0; i < n; i++) {
            names[i] = data.classDisplayName(i);
        }
        visible = restoreVisibility(names, rememberedVisibility);
        for (int i = 0; i < n; i++) {
            rememberedVisibility.put(names[i], visible[i]);
            rows.getChildren().add(makeRow(data, i, visible[i]));
        }
    }

    /**
     * Visibility for a new class list, from what was remembered of the old one.
     * <p>
     * A name not seen before is visible. A remembered set that would hide EVERY
     * class in the new list is ignored and everything is shown, because an empty
     * cloud after an axis change looks like a failed read and has no visible
     * cause to click back.
     *
     * @param names      class display names, in display order
     * @param remembered name -> shown, from the previous dataset
     * @return one flag per name, index-aligned
     */
    static boolean[] restoreVisibility(String[] names,
                                       java.util.Map<String, Boolean> remembered) {
        boolean[] out = new boolean[names.length];
        boolean anyShown = false;
        for (String name : names) {
            if (remembered.getOrDefault(name, Boolean.TRUE)) {
                anyShown = true;
                break;
            }
        }
        for (int i = 0; i < names.length; i++) {
            out[i] = !anyShown || remembered.getOrDefault(names[i], Boolean.TRUE);
        }
        return out;
    }


    private HBox makeRow(PointCloudData data, int classIndex, boolean shown) {
        CheckBox cb = new CheckBox();
        cb.setSelected(shown);
        cb.setTooltip(new javafx.scene.control.Tooltip("Show or hide this class in the cloud."));
        String className = data.classDisplayName(classIndex);
        cb.selectedProperty().addListener((obs, was, now) -> {
            visible[classIndex] = now;
            rememberedVisibility.put(className, now);
            onChange.run();
        });

        Color c = data.palette[classIndex];
        Rectangle swatch = new Rectangle(12, 12, c == null ? Color.GRAY : c);
        swatch.setStroke(Color.gray(0.4));
        javafx.scene.control.Tooltip.install(
                swatch,
                new javafx.scene.control.Tooltip(
                        "Color comes from this class in QuPath. Change it in the class list to recolor the cloud."));
        // The name absorbs the squeeze and elides from the LEFT, because the
        // tail is what distinguishes one row from another. A host that
        // namespaces its classes -- QP-CAT prefixes every applied class with
        // the result name, "auto_20260927_015057_hdbscan: Cluster 0" -- made
        // all seven rows of this ~160 px column read "auto_20260927_015057..."
        // with the cluster number and the count clipped off the right, which is
        // least readable immediately after the action that fills it. Eliding
        // rather than parsing the name: this is a generic viewer and the
        // "<namespace>: <class>" shape is one host's convention, not a contract.
        Label name = new Label(className);
        name.setTextOverrun(OverrunStyle.LEADING_ELLIPSIS);
        name.setTooltip(new javafx.scene.control.Tooltip(className));
        name.setMinWidth(0);
        name.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(name, Priority.ALWAYS);

        Label count = new Label(String.valueOf(data.classCounts[classIndex]));
        count.setStyle("-fx-text-fill: -fx-mid-text-color;");
        // Never squeezed out: the count is how you check an apply landed on the
        // number of cells you expected.
        count.setMinWidth(Region.USE_PREF_SIZE);

        HBox row = new HBox(6, cb, swatch, name, count);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private void setAll(boolean shown) {
        for (int i = 0; i < rows.getChildren().size(); i++) {
            HBox row = (HBox) rows.getChildren().get(i);
            CheckBox cb = (CheckBox) row.getChildren().get(0);
            cb.setSelected(shown); // fires listener -> updates mask + onChange
        }
    }

    /** Per-class visibility mask (index-aligned with the class list). */
    public boolean[] getVisibleMask() {
        return visible;
    }
}
