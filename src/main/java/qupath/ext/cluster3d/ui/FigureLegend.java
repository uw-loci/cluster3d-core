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

import java.util.ArrayList;
import java.util.List;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;

/**
 * A plot beside a full legend, for saving as a figure. The legend wraps into columns
 * no taller than the plot, so a long class list keeps the figure a sensible shape.
 */
public final class FigureLegend {

    private static final double ROW_HEIGHT = 16;
    private static final double TITLE_HEIGHT = 20;

    private FigureLegend() {}

    /**
     * One legend row.
     *
     * @param color the class color
     * @param name  the class name
     * @param count how many cells it holds
     */
    public record Entry(Color color, String name, int count) {}

    /**
     * How many legend rows fit in one column beside a plot.
     *
     * @param plotHeight height of the plot
     * @return the rows per column, at least 1
     */
    static int rowsPerColumn(double plotHeight) {
        return Math.max(1, (int) ((plotHeight - TITLE_HEIGHT) / ROW_HEIGHT));
    }

    /**
     * Build the figure: dark text on white, whatever theme QuPath is using.
     *
     * @param plot       the rendered plot
     * @param plotWidth  width to show it at
     * @param plotHeight height to show it at
     * @param title      legend title
     * @param entries    every row, in order
     * @return a node in a scene of its own, to snapshot
     */
    public static Node figure(Image plot, double plotWidth, double plotHeight, String title, List<Entry> entries) {
        ImageView view = new ImageView(plot);
        view.setFitWidth(plotWidth);
        view.setFitHeight(plotHeight);

        Label heading = blackLabel(title);
        heading.setStyle(heading.getStyle() + " -fx-font-weight: bold; -fx-font-size: 11px;");
        VBox legend = new VBox(4, heading, columns(entries, rowsPerColumn(plotHeight)));

        HBox figure = new HBox(10, view, legend);
        figure.setPadding(new Insets(8));
        figure.setStyle("-fx-background-color: white;");
        // Controls paint nothing until a scene has given them a skin.
        new Scene(figure);
        figure.applyCss();
        figure.layout();
        return figure;
    }

    private static HBox columns(List<Entry> entries, int rowsPerColumn) {
        List<Node> columns = new ArrayList<>();
        for (int start = 0; start < entries.size(); start += rowsPerColumn) {
            VBox column = new VBox();
            for (Entry entry : entries.subList(start, Math.min(start + rowsPerColumn, entries.size()))) {
                column.getChildren().add(row(entry));
            }
            columns.add(column);
        }
        HBox box = new HBox(14);
        box.getChildren().setAll(columns);
        return box;
    }

    private static HBox row(Entry entry) {
        Rectangle swatch = new Rectangle(11, 11, entry.color());
        swatch.setArcWidth(3);
        swatch.setArcHeight(3);
        swatch.setStroke(Color.gray(0.6));
        Label count = blackLabel(String.valueOf(entry.count()));
        Region spacer = new Region();
        spacer.setMinWidth(8);
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox row = new HBox(5, swatch, blackLabel(entry.name()), spacer, count);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setMinHeight(ROW_HEIGHT);
        row.setPrefHeight(ROW_HEIGHT);
        return row;
    }

    private static Label blackLabel(String text) {
        Label label = new Label(text);
        label.setStyle("-fx-font-size: 10.5px; -fx-text-fill: black;");
        label.setMinWidth(Region.USE_PREF_SIZE);
        return label;
    }
}
