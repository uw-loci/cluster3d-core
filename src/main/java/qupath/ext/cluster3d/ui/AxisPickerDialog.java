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

import java.util.List;
import java.util.Optional;
import javafx.geometry.Insets;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Window;

/**
 * WINDOW_MODAL "Choose axes" popup: three combos of numeric measurement columns
 * plus a "remember" checkbox. Apply is disabled until all three are set; an inline
 * (non-blocking) warning shows when two or more axes are the same.
 */
public final class AxisPickerDialog {

    private AxisPickerDialog() {}

    /** The user's choice returned by {@link #show}. */
    public static final class Result {
        public final String x;
        public final String y;
        public final String z;
        public final boolean remember;

        public Result(String x, String y, String z, boolean remember) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.remember = remember;
        }
    }

    /**
     * Show the picker and return the chosen axes, or empty if cancelled.
     *
     * @param owner          owning window (for window-modality); may be null
     * @param numericColumns the union of numeric measurement names
     * @param initX          pre-selected X (may be null)
     * @param initY          pre-selected Y (may be null)
     * @param initZ          pre-selected Z (may be null)
     * @param initRemember   initial state of the remember checkbox
     */
    public static Optional<Result> show(
            Window owner, List<String> numericColumns, String initX, String initY, String initZ, boolean initRemember) {

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Choose axes");
        dialog.setHeaderText("Pick three numeric measurements for X / Y / Z.");
        if (owner != null) {
            dialog.initOwner(owner);
        }
        dialog.initModality(Modality.WINDOW_MODAL);

        ButtonType applyType = new ButtonType("Apply", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, applyType);

        ComboBox<String> cx = new ComboBox<>();
        ComboBox<String> cy = new ComboBox<>();
        ComboBox<String> cz = new ComboBox<>();
        for (ComboBox<String> c : List.of(cx, cy, cz)) {
            c.getItems().setAll(numericColumns);
            c.setMaxWidth(Double.MAX_VALUE);
        }
        cx.setValue(initX);
        cy.setValue(initY);
        cz.setValue(initZ);
        cx.setTooltip(new Tooltip("Numeric measurement to use for this axis."));
        cy.setTooltip(new Tooltip("Numeric measurement to use for this axis."));
        cz.setTooltip(new Tooltip("Numeric measurement to use for this axis."));

        CheckBox remember = new CheckBox("Use these axes automatically next time you open this project.");
        remember.setSelected(initRemember);

        Label warning = new Label("");
        // Theme-aware, AA-contrast warning color (matches the owner window's theme).
        boolean dark = ThemeUtils.isDark(owner);
        warning.setStyle(ThemeUtils.warningStyle(dark));

        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(8);
        grid.setPadding(new Insets(10));
        grid.addRow(0, new Label("X axis"), cx);
        grid.addRow(1, new Label("Y axis"), cy);
        grid.addRow(2, new Label("Z axis"), cz);

        VBox content = new VBox(8, grid, remember, warning);
        content.setPadding(new Insets(6));
        dialog.getDialogPane().setContent(content);

        Runnable validate = () -> {
            boolean allSet = cx.getValue() != null && cy.getValue() != null && cz.getValue() != null;
            dialog.getDialogPane().lookupButton(applyType).setDisable(!allSet);
            boolean sameAxis = allSet
                    && (cx.getValue().equals(cy.getValue())
                            || cx.getValue().equals(cz.getValue())
                            || cy.getValue().equals(cz.getValue()));
            warning.setText(sameAxis ? "Axes should differ for a meaningful 3D view." : "");
        };
        cx.valueProperty().addListener((o, a, b) -> validate.run());
        cy.valueProperty().addListener((o, a, b) -> validate.run());
        cz.valueProperty().addListener((o, a, b) -> validate.run());
        validate.run();

        Optional<ButtonType> res = dialog.showAndWait();
        if (res.isPresent() && res.get() == applyType) {
            return Optional.of(new Result(cx.getValue(), cy.getValue(), cz.getValue(), remember.isSelected()));
        }
        return Optional.empty();
    }
}
