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

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.IntStream;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
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

    /** The filter box appears above this many classes. */
    static final int FILTER_MIN_CLASSES = 12;

    private static final String DIGIT_BOUNDARY = "(?<=\\D)(?=\\d)|(?<=\\d)(?=\\D)";

    private final VBox rows = new VBox(2);
    private final List<String> rowNames = new ArrayList<>();
    private final Label header = new Label("CLASSES");
    private final TextField filter = new TextField();
    private boolean[] visible = new boolean[0];
    private Runnable onChange = () -> {};
    // Class name -> whether it was shown, carried across a reload. Changing an
    // axis measurement re-reads the detections and rebuilds this legend, and
    // rebuilding used to turn every class back on -- so narrowing to two classes
    // and then looking at them against a different measurement, which is the
    // normal way to use this, silently put all twenty back.
    private final java.util.Map<String, Boolean> rememberedVisibility = new java.util.HashMap<>();

    public ClassLegend() {
        setSpacing(6);
        setPadding(new Insets(6));
        header.setStyle("-fx-font-weight: bold;");

        Hyperlink all = new Hyperlink("All");
        all.setTooltip(new javafx.scene.control.Tooltip("Show every class listed."));
        all.setOnAction(e -> setAll(true));
        Hyperlink none = new Hyperlink("None");
        none.setTooltip(new javafx.scene.control.Tooltip("Hide every class listed."));
        none.setOnAction(e -> setAll(false));
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox controls = new HBox(4, header, spacer, all, none);
        controls.setAlignment(Pos.CENTER_LEFT);

        ScrollPane scroll = new ScrollPane(rows);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportHeight(320);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        filter.setPromptText("Filter classes...");
        filter.setTooltip(new javafx.scene.control.Tooltip(
                "List only the classes containing this text. All and None then act on those."));
        filter.textProperty().addListener((obs, was, now) -> applyFilter());
        showFilter(false);

        getChildren().addAll(controls, filter, scroll);
    }

    private void showFilter(boolean show) {
        filter.setVisible(show);
        filter.setManaged(show);
        if (!show) {
            filter.clear();
        }
    }

    /**
     * The order to list classes in: by name, with numbers compared as numbers, so
     * {@code Cluster 2} comes before {@code Cluster 10}.
     *
     * @param names class names
     * @return their indices in display order
     */
    static int[] displayOrder(String[] names) {
        return IntStream.range(0, names.length)
                .boxed()
                .sorted((a, b) -> compareNames(names[a], names[b]))
                .mapToInt(Integer::intValue)
                .toArray();
    }

    private static int compareNames(String a, String b) {
        String[] left = a.split(DIGIT_BOUNDARY);
        String[] right = b.split(DIGIT_BOUNDARY);
        for (int i = 0; i < Math.min(left.length, right.length); i++) {
            int order = isNumber(left[i]) && isNumber(right[i])
                    ? new BigInteger(left[i]).compareTo(new BigInteger(right[i]))
                    : left[i].compareToIgnoreCase(right[i]);
            if (order != 0) {
                return order;
            }
        }
        return Integer.compare(left.length, right.length);
    }

    private static boolean isNumber(String token) {
        return !token.isEmpty() && token.charAt(0) >= '0' && token.charAt(0) <= '9';
    }

    /** Whether a class name passes the filter text; case does not matter. */
    static boolean matches(String name, String query) {
        return query == null
                || query.isBlank()
                || name.toLowerCase(Locale.ROOT).contains(query.trim().toLowerCase(Locale.ROOT));
    }

    private void applyFilter() {
        String query = filter.getText();
        int matching = 0;
        for (int i = 0; i < rowNames.size(); i++) {
            boolean match = matches(rowNames.get(i), query);
            Node row = rows.getChildren().get(i);
            row.setVisible(match);
            row.setManaged(match);
            matching += match ? 1 : 0;
        }
        header.setText(
                query == null || query.isBlank() ? "CLASSES" : "CLASSES (" + matching + " of " + rowNames.size() + ")");
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
        rowNames.clear();
        if (data == null || data.classCount() == 0) {
            visible = new boolean[0];
            showFilter(false);
            applyFilter();
            return;
        }
        int n = data.classCount();
        String[] names = new String[n];
        for (int i = 0; i < n; i++) {
            names[i] = data.classDisplayName(i);
        }
        visible = restoreVisibility(names, rememberedVisibility);
        for (int i : displayOrder(names)) {
            rememberedVisibility.put(names[i], visible[i]);
            rows.getChildren().add(makeRow(data, i, visible[i]));
            rowNames.add(names[i]);
        }
        showFilter(n > FILTER_MIN_CLASSES);
        applyFilter();
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
    static boolean[] restoreVisibility(String[] names, java.util.Map<String, Boolean> remembered) {
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
            if (!row.isManaged()) {
                continue; // filtered out
            }
            CheckBox cb = (CheckBox) row.getChildren().get(0);
            cb.setSelected(shown); // fires listener -> updates mask + onChange
        }
    }

    /** Per-class visibility mask (index-aligned with the class list). */
    public boolean[] getVisibleMask() {
        return visible;
    }
}
