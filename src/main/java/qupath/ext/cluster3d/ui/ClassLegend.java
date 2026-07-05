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

    /** Rebuild the rows for a new dataset. All classes start visible. */
    public void setData(PointCloudData data) {
        rows.getChildren().clear();
        if (data == null || data.classCount() == 0) {
            visible = new boolean[0];
            return;
        }
        int n = data.classCount();
        visible = new boolean[n];
        for (int i = 0; i < n; i++) {
            visible[i] = true;
            rows.getChildren().add(makeRow(data, i));
        }
    }

    private HBox makeRow(PointCloudData data, int classIndex) {
        CheckBox cb = new CheckBox();
        cb.setSelected(true);
        cb.setTooltip(new javafx.scene.control.Tooltip("Show or hide this class in the cloud."));
        cb.selectedProperty().addListener((obs, was, now) -> {
            visible[classIndex] = now;
            onChange.run();
        });

        Color c = data.palette[classIndex];
        Rectangle swatch = new Rectangle(12, 12, c == null ? Color.GRAY : c);
        swatch.setStroke(Color.gray(0.4));
        javafx.scene.control.Tooltip.install(
                swatch,
                new javafx.scene.control.Tooltip(
                        "Color comes from this class in QuPath. Change it in the class list to recolor the cloud."));
        Label name = new Label(data.classDisplayName(classIndex));
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label count = new Label(String.valueOf(data.classCounts[classIndex]));
        count.setStyle("-fx-text-fill: -fx-mid-text-color;");

        HBox row = new HBox(6, cb, swatch, name, spacer, count);
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
