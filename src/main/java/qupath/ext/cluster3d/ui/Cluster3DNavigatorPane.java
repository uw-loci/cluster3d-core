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

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Optional;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.beans.value.ChangeListener;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.RadioButton;
import javafx.scene.control.Separator;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TitledPane;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.cluster3d.io.AxisAutoDetect;
import qupath.ext.cluster3d.io.DetectionReader;
import qupath.ext.cluster3d.model.CellRef;
import qupath.ext.cluster3d.model.PointCloudData;
import qupath.ext.cluster3d.prefs.Cluster3DNavPreferences;
import qupath.ext.cluster3d.service.CellCropService;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;

/**
 * Reusable, host-agnostic 3D-navigator content pane (the Apache-2.0 "CoreLib"
 * component). This {@link BorderPane} owns the whole viewer UI -- top control strip,
 * center Canvas, right legend + cell preview, bottom status bar -- follows the active
 * image via QuPath property listeners, reads detections off the FX thread, and drives
 * the point cloud, LOD thumbnails, and navigation.
 *
 * <p>It is deliberately container-agnostic: drop it in a {@code Stage} (the standalone
 * navigator) OR a {@code Tab} (a host plugin). Public API is intentionally small:
 * {@link #Cluster3DNavigatorPane(QuPathGUI)}, the node itself (this pane),
 * {@link #titleProperty()}, {@link #initialLoad()}, {@link #reload()}, and
 * {@link #dispose()}. The host owns window geometry, titling, and lifecycle; call
 * {@link #initialLoad()} once after the pane is shown and {@link #dispose()} on close.</p>
 */
public class Cluster3DNavigatorPane extends BorderPane {

    private static final Logger logger = LoggerFactory.getLogger(Cluster3DNavigatorPane.class);

    private final QuPathGUI qupath;

    private final PointCloudView cloudView;
    private final ClassLegend legend = new ClassLegend();
    private final CellCropService cropService;

    private final StringProperty title = new SimpleStringProperty("Cluster 3D Navigator");

    private final ToggleGroup modeGroup = new ToggleGroup();
    private final RadioButton modeCurrent = new RadioButton("Current image");
    private final RadioButton modeProject = new RadioButton("Project images...");
    private final Button selectImagesButton = new Button("Select images...");
    // Scope controls (Mode radios + Select images) grouped so a host that fixes the
    // scope can hide them wholesale via initializeForHost.
    private final HBox modeControls = new HBox(6);
    private final Label pointsLabel = new Label("Points: 0 / 0");
    // View dimensionality (flat 2D scatter vs rotatable 3D cloud).
    private final ToggleGroup dimGroup = new ToggleGroup();
    private final RadioButton dim3D = new RadioButton("3D");
    private final RadioButton dim2D = new RadioButton("2D");
    private boolean twoD = false; // effective state (pref OR forced when < 3 numeric axes)
    private boolean suppressDimEvents = false;
    private final ComboBox<String> axisX = new ComboBox<>();
    private final ComboBox<String> axisY = new ComboBox<>();
    private final ComboBox<String> axisZ = new ComboBox<>();
    private final Label zLabel = new Label("Z");
    // Non-blocking warning shown when a 2D view plots two axes of a 3D embedding.
    private final Label twoOfThreeWarn = new Label("");
    private final Label autoTag = new Label("");
    private final Label statusLabel = new Label("");
    private final Label noteLabel = new Label("");
    private boolean partialReadError = false;
    private boolean limited = false;
    private final ImageView previewView = new ImageView();
    private final Label previewCaption = new Label("");
    private final Button updateFromViewerButton = new Button("Update from viewer");

    private final StackPane centerStack = new StackPane();
    private final VBox busyBox = new VBox(8);
    private final Label busyLabel = new Label("Reading...");

    // Read state. Per-cell measurement maps are NOT retained for the session.
    private List<String> numericAxes = List.of();
    private String[] requestedAxes; // explicit user axis choice honored across a re-read
    private PointCloudData data;
    private int lastPreviewIndex = -1; // last cell shown in the Cell preview panel
    private boolean suppressAxisEvents = false;
    // Session-remembered project-image subset for "Project images..." mode.
    private List<ProjectImageEntry<BufferedImage>> selectedEntries;
    private boolean suppressModeEvents = false;
    private long previewToken = 0;
    // Host-embed mode (e.g. a QP-CAT tab): the host fixes the image scope, so the
    // pane never prompts and never reads the persisted mode pref. See initializeForHost.
    private boolean hostMode = false;
    // The ImageData last read in current-image mode. Guards against a spurious
    // imageDataProperty fire (same image) triggering a needless reload -> setData ->
    // clearImageCache, which would discard in-flight thumbnail crops mid-load.
    private ImageData<BufferedImage> lastReadImageData;

    private final ChangeListener<ImageData<BufferedImage>> imageListener;
    private final ChangeListener<Project<BufferedImage>> projectListener;

    public Cluster3DNavigatorPane(QuPathGUI qupath) {
        this.qupath = qupath;
        this.cropService = new CellCropService(qupath);
        this.cloudView = new PointCloudView(qupath);

        buildUi();

        imageListener = (obs, oldData, newData) -> Platform.runLater(this::onActiveImageChanged);
        projectListener = (obs, oldP, newP) -> Platform.runLater(this::onActiveImageChanged);
        qupath.imageDataProperty().addListener(imageListener);
        qupath.projectProperty().addListener(projectListener);
    }

    /** Live "Cluster 3D Navigator - {image}" title; a host may bind its Stage/Tab title to this. */
    public ReadOnlyStringProperty titleProperty() {
        return title;
    }

    /**
     * First load after the pane is shown: refresh theme-aware colors and, if starting
     * in project mode, prompt for the image subset over the now-visible window. Call
     * this once, after the pane's window is showing.
     */
    public void initialLoad() {
        applyAccentColors();
        if (modeProject.isSelected()) {
            selectImagesButton.setDisable(false);
            if (!promptImageSelection()) {
                // User cancelled the initial picker -> fall back to current-image mode.
                suppressModeEvents = true;
                modeCurrent.setSelected(true);
                suppressModeEvents = false;
                selectImagesButton.setDisable(true);
                Cluster3DNavPreferences.modeProperty().set("current");
            }
        }
        reload();
    }

    /**
     * Host-embed entry point (use INSTEAD of {@link #initialLoad()} when a host owns the
     * image scope, e.g. a QP-CAT results tab bound to the clustered images).
     *
     * <p>Unlike {@link #initialLoad()}, this NEVER prompts for images and does NOT read the
     * persisted mode preference. It hides the scope controls (the Mode radios + "Select
     * images..." button), then reads exactly the given {@code entries} off the FX thread
     * (like project mode). If {@code entries} is null or empty it falls back to the CURRENT
     * image only -- still with no prompt. Everything else (axis auto-detect, render,
     * click-to-navigate, thumbnails, update-from-viewer) behaves exactly as usual.</p>
     *
     * @param entries the fixed image scope (may be null/empty -> current image only)
     */
    public void initializeForHost(List<ProjectImageEntry<BufferedImage>> entries) {
        this.hostMode = true;
        this.selectedEntries = (entries == null) ? null : new java.util.ArrayList<>(entries);
        // The host fixes the scope, so the mode radios + Select images button are
        // meaningless here -- hide them (and reclaim their layout space).
        modeControls.setVisible(false);
        modeControls.setManaged(false);
        applyAccentColors();
        reload();
    }

    /** The window currently hosting this pane, or null if not attached/shown. */
    private Window ownerWindow() {
        Scene scene = getScene();
        return scene == null ? null : scene.getWindow();
    }

    /** Apply AA-contrast accent colors that suit the current QuPath theme. */
    private void applyAccentColors() {
        boolean dark = ThemeUtils.isDark(getScene());
        autoTag.setStyle(ThemeUtils.autoDetectStyle(dark));
        updateNotes();
    }

    // ---------------- UI ----------------

    private void buildUi() {
        // Top control strip.
        modeCurrent.setToggleGroup(modeGroup);
        modeProject.setToggleGroup(modeGroup);
        modeCurrent.setTooltip(new Tooltip("Show only cells from the image open in the viewer."));
        modeProject.setTooltip(new Tooltip(
                "Show cells from a subset of project images you pick. Reading many images may take a moment."));
        boolean projectMode =
                "project".equals(Cluster3DNavPreferences.modeProperty().get());
        modeProject.setDisable(qupath.getProject() == null);
        if (projectMode && qupath.getProject() != null) {
            modeProject.setSelected(true);
        } else {
            modeCurrent.setSelected(true);
        }
        modeGroup.selectedToggleProperty().addListener((o, a, b) -> onModeChanged());

        selectImagesButton.setTooltip(new Tooltip("Pick which project images to include in the cloud."));
        selectImagesButton.setDisable(!modeProject.isSelected());
        selectImagesButton.setOnAction(e -> {
            if (promptImageSelection()) {
                reload();
            }
        });

        pointsLabel.setTooltip(
                new Tooltip("Points currently shown out of the total read. Hidden classes are not counted as shown."));

        Button reset = new Button("Reset view");
        reset.setTooltip(new Tooltip("Reset rotation, zoom, and pan to fit all points."));
        reset.setOnAction(e -> cloudView.resetView());

        // Scope controls grouped so a host that fixes the scope can hide them all.
        modeControls.setAlignment(Pos.CENTER_LEFT);
        modeControls.getChildren().setAll(new Label("Mode:"), modeCurrent, modeProject, selectImagesButton);

        // Performance controls (upper-right): per-image cell limit + seed + a help button.
        Spinner<Integer> cellLimit = intSpinner(
                0, 1000000, Cluster3DNavPreferences.cellLimitPerImageProperty().get(), 500);
        cellLimit.setTooltip(new Tooltip("Limit how many cells per image are loaded. An option for slower "
                + "computers and laptops: it improves performance but WILL limit the amount of data shown "
                + "(a performance tradeoff)."));
        cellLimit.valueProperty().addListener((o, a, b) -> {
            Cluster3DNavPreferences.cellLimitPerImageProperty().set(b);
            reload(); // re-read + re-subsample off the FX thread
        });

        Spinner<Integer> seed = intSpinner(
                0,
                Integer.MAX_VALUE,
                Cluster3DNavPreferences.subsampleSeedProperty().get());
        seed.setTooltip(new Tooltip(
                "Random seed for which cells are shown when a cell limit is set. " + "Change it to resample."));
        seed.valueProperty().addListener((o, a, b) -> {
            Cluster3DNavPreferences.subsampleSeedProperty().set(b);
            reload();
        });

        Button limitHelp = new Button("?");
        limitHelp.setTooltip(new Tooltip("How the per-image cell limit chooses cells."));
        limitHelp.setOnAction(e -> Dialogs.showMessageDialog(
                "Cell limit per image",
                "When a cell limit is set, cells are chosen per image so every cluster stays represented: "
                        + "each cluster keeps at least its representative cells (spread across the cluster by "
                        + "farthest-point sampling), guaranteeing a minimum per cluster; the remaining budget is "
                        + "filled at random using the seed. Clusters present in the image are never dropped "
                        + "entirely (up to the limit)."));

        HBox limitRow = new HBox(4, new Label("Cell limit per image"), cellLimit, new Label("Seed"), seed, limitHelp);
        limitRow.setAlignment(Pos.CENTER_LEFT);

        // View dimensionality toggle. 2D = flat X/Y scatter (for genuine 2D embeddings),
        // 3D = rotatable cloud (needs a 3-component embedding). Persisted; the 3D option is
        // disabled when the data has fewer than 3 numeric measurements.
        dim3D.setToggleGroup(dimGroup);
        dim2D.setToggleGroup(dimGroup);
        dim3D.setTooltip(new Tooltip("Rotatable 3D cloud. Needs a 3-component embedding (e.g. UMAP1/2/3)."));
        dim2D.setTooltip(new Tooltip(
                "Flat top-down X/Y scatter. Use a genuine 2D embedding (e.g. a 2D UMAP), "
                        + "NOT two axes of a 3D embedding."));
        twoD = "2d".equals(Cluster3DNavPreferences.dimensionsProperty().get());
        suppressDimEvents = true;
        (twoD ? dim2D : dim3D).setSelected(true);
        suppressDimEvents = false;
        dimGroup.selectedToggleProperty().addListener((o, a, b) -> onDimensionChanged());
        HBox dimControls = new HBox(6, new Label("View:"), dim3D, dim2D);
        dimControls.setAlignment(Pos.CENTER_LEFT);
        cloudView.setTwoD(twoD);

        axisX.setTooltip(new Tooltip("Measurement plotted on the X axis."));
        axisY.setTooltip(new Tooltip("Measurement plotted on the Y axis."));
        axisZ.setTooltip(new Tooltip("Measurement plotted on the depth (Z) axis."));
        axisX.setAccessibleText("Measurement for the X axis");
        axisY.setAccessibleText("Measurement for the Y axis");
        axisZ.setAccessibleText("Measurement for the depth Z axis");
        for (ComboBox<String> c : List.of(axisX, axisY, axisZ)) {
            c.setPrefWidth(140);
            c.valueProperty().addListener((o, a, b) -> onAxisComboChanged());
        }
        autoTag.setStyle(ThemeUtils.autoDetectStyle(false));
        autoTag.setTooltip(
                new Tooltip(
                        "These axes were detected automatically from measurement names. Change any of them to override."));
        Button changeAxes = new Button("Change axes...");
        changeAxes.setTooltip(new Tooltip("Pick which numeric measurements map to the axes."));
        changeAxes.setOnAction(e -> openAxisPicker());

        // Quick toggle in the top strip (grouped with the cell-limit / seed / points controls;
        // setShowCellImages also pre-warms the representatives).
        CheckBox cellImages = new CheckBox("Show cell images");
        cellImages.setSelected(Cluster3DNavPreferences.showCellImagesProperty().get());
        cellImages.setTooltip(
                new Tooltip("Draw the actual cell crops at their 3D positions when zoomed in. Off = points only."));
        cellImages.selectedProperty().addListener((o, a, b) -> {
            Cluster3DNavPreferences.showCellImagesProperty().set(b);
            cloudView.setShowCellImages(b);
        });
        cloudView.setShowCellImages(cellImages.isSelected());

        HBox axisRow = new HBox(
                8, new Label("Axes:"), new Label("X"), axisX, new Label("Y"), axisY, zLabel, axisZ, autoTag,
                changeAxes);
        axisRow.setAlignment(Pos.CENTER_LEFT);

        HBox pointsReset = new HBox(8, pointsLabel, reset);
        pointsReset.setAlignment(Pos.CENTER_LEFT);

        // Two-of-three warning (hidden until it applies), styled as a warning + wrapping.
        twoOfThreeWarn.setStyle(ThemeUtils.warningStyle(false));
        twoOfThreeWarn.setWrapText(true);
        twoOfThreeWarn.setVisible(false);
        twoOfThreeWarn.setManaged(false);

        // A FlowPane so the control GROUPS wrap to a new line at narrow widths instead of
        // clipping off the right edge -- each HBox group stays intact and wraps as a unit,
        // so the Cell limit / Seed / "?" controls are always reachable at the min 820 width.
        // Vertical separators divide the three logical groups: scope | view+axes |
        // display+limit+points. (On a wrap, a separator may land at a line edge -- cosmetic.)
        FlowPane controls = new FlowPane(
                12,
                6,
                modeControls,
                verticalSeparator(),
                dimControls,
                axisRow,
                verticalSeparator(),
                cellImages,
                limitRow,
                pointsReset);
        controls.setAlignment(Pos.CENTER_LEFT);
        controls.setPadding(new Insets(6));

        VBox top = new VBox(controls, twoOfThreeWarn, buildDisplayOptions());
        setTop(top);

        // Center: canvas + busy overlay.
        cloudView.setPrefSize(700, 520);
        legend.setOnChange(this::onLegendChanged);
        cloudView.setOnPointClicked(this::loadPreview);
        cloudView.setOnPointHovered(this::loadPreview);
        // Crop source for the in-cloud VEST-style thumbnails (loaded off the FX thread).
        cloudView.setCropSource(
                cropService::readCrop,
                () -> Cluster3DNavPreferences.cropScaleProperty().get());

        ProgressIndicator spinner = new ProgressIndicator();
        spinner.setMaxSize(60, 60);
        busyBox.setAlignment(Pos.CENTER);
        busyBox.getChildren().addAll(spinner, busyLabel);
        busyBox.setVisible(false);
        busyBox.setMouseTransparent(true);

        // Top-center "Image still populating" pill while thumbnails are loading in.
        Label populating = new Label("Image still populating");
        populating.setStyle("-fx-background-color: rgba(0,0,0,0.55); -fx-text-fill: white; "
                + "-fx-background-radius: 12; -fx-padding: 4 12 4 12; -fx-font-size: 11px;");
        populating.setMouseTransparent(true);
        StackPane.setAlignment(populating, Pos.TOP_CENTER);
        StackPane.setMargin(populating, new Insets(8, 0, 0, 0));
        populating.visibleProperty().bind(cloudView.populatingProperty());
        populating.managedProperty().bind(cloudView.populatingProperty());

        centerStack.getChildren().addAll(cloudView, busyBox, populating);
        setCenter(centerStack);

        // Right: legend + cell preview (crop loads on click by default).
        previewView.setPreserveRatio(true);
        previewView.setFitWidth(220);
        previewView.setFitHeight(220);
        Tooltip.install(previewView, new Tooltip("Image crop of the last cell you clicked or hovered."));
        Label previewHeader = new Label("Cell preview");
        previewHeader.setStyle("-fx-font-weight: bold;");
        updateFromViewerButton.setTooltip(new Tooltip(
                "Re-render the cell preview using the viewer's current channel visibility and brightness/contrast."));
        updateFromViewerButton.setDisable(true); // enabled once a cell has been previewed
        updateFromViewerButton.setOnAction(e -> refreshPreviewFromViewer());
        VBox previewBox = new VBox(4, previewHeader, previewView, previewCaption, updateFromViewerButton);
        previewBox.setPadding(new Insets(6));
        VBox right = new VBox(6, legend, previewBox);
        VBox.setVgrow(legend, Priority.ALWAYS);
        right.setPrefWidth(260);
        right.setMinWidth(260);
        setRight(right);

        // Bottom status/help bar.
        statusLabel.setText(
                "Rotate: left-drag | Zoom: scroll | Pan: middle-drag or Shift+drag | Click a point to center + select");
        noteLabel.setVisible(false);
        noteLabel.setManaged(false);
        Region bottomSpacer = new Region();
        HBox.setHgrow(bottomSpacer, Priority.ALWAYS);
        HBox bottomBar = new HBox(10, statusLabel, bottomSpacer, noteLabel);
        bottomBar.setAlignment(Pos.CENTER_LEFT);
        bottomBar.setPadding(new Insets(6));
        setBottom(bottomBar);
    }

    private TitledPane buildDisplayOptions() {
        Spinner<Double> cropScale = doubleSpinner(
                1.0, 8.0, Cluster3DNavPreferences.cropScaleProperty().get(), 0.5);
        cropScale.setTooltip(new Tooltip("Size of the preview crop as a multiple of the cell's bounding box."));
        cropScale
                .valueProperty()
                .addListener(
                        (o, a, b) -> Cluster3DNavPreferences.cropScaleProperty().set(b));

        Spinner<Double> pointSize = doubleSpinner(
                1.0, 6.0, Cluster3DNavPreferences.pointSizeProperty().get(), 0.5);
        pointSize.setTooltip(new Tooltip("On-screen size of each plotted point."));
        pointSize.valueProperty().addListener((o, a, b) -> {
            Cluster3DNavPreferences.pointSizeProperty().set(b);
            cloudView.setPointSize(b);
        });
        cloudView.setPointSize(Cluster3DNavPreferences.pointSizeProperty().get());

        CheckBox depthCue = new CheckBox("Shade points by depth");
        depthCue.setSelected(Cluster3DNavPreferences.depthCueProperty().get());
        depthCue.setTooltip(new Tooltip("Draw nearer points larger and brighter so the cloud reads as 3D."));
        depthCue.selectedProperty().addListener((o, a, b) -> {
            Cluster3DNavPreferences.depthCueProperty().set(b);
            cloudView.setDepthCue(b);
        });
        cloudView.setDepthCue(depthCue.isSelected());

        CheckBox hoverPreview = new CheckBox("Preview crop on hover");
        hoverPreview.setSelected(Cluster3DNavPreferences.hoverPreviewProperty().get());
        hoverPreview.setTooltip(
                new Tooltip("Load a crop thumbnail while hovering, not only on click. May be slow on dense clouds."));
        hoverPreview.selectedProperty().addListener((o, a, b) -> {
            Cluster3DNavPreferences.hoverPreviewProperty().set(b);
            cloudView.setHoverPreviewEnabled(b);
        });
        cloudView.setHoverPreviewEnabled(hoverPreview.isSelected());

        CheckBox tripod = new CheckBox("Show axis tripod");
        tripod.setSelected(Cluster3DNavPreferences.showTripodProperty().get());
        tripod.setTooltip(new Tooltip("Show a small X/Y/Z direction marker in the corner."));
        tripod.selectedProperty().addListener((o, a, b) -> {
            Cluster3DNavPreferences.showTripodProperty().set(b);
            cloudView.setShowTripod(b);
        });
        cloudView.setShowTripod(tripod.isSelected());

        // Draw each cell's segmentation outline (its detection ROI) on the crop, in the cell's
        // class color -- in both the in-cloud thumbnails and the Cell preview. Baked into the
        // crop by CellCropService, so toggling clears the crop caches and re-renders.
        CheckBox outlines = new CheckBox("Show detection outlines");
        outlines.setSelected(Cluster3DNavPreferences.showDetectionOutlinesProperty().get());
        outlines.setTooltip(new Tooltip("Draw each cell's segmentation boundary on its crop image "
                + "(thumbnails + Cell preview). Helps spot clusters caused by segmentation errors."));
        outlines.selectedProperty().addListener((o, a, b) -> {
            Cluster3DNavPreferences.showDetectionOutlinesProperty().set(b);
            cropService.setShowOutlines(b);
            cloudView.clearImageCache(); // in-cloud thumbnails re-bake
            if (lastPreviewIndex >= 0) {
                loadPreview(lastPreviewIndex); // re-render the current preview crop
            }
        });
        cropService.setShowOutlines(outlines.isSelected());

        Spinner<Integer> repsPerCluster = intSpinner(
                0,
                5,
                Cluster3DNavPreferences.representativesPerClusterProperty().get());
        repsPerCluster.setTooltip(
                new Tooltip("Cells per cluster shown as images even when zoomed out (0 = only when zoomed in). "
                        + "Requires \"Show cell images\"."));
        repsPerCluster.valueProperty().addListener((o, a, b) -> {
            Cluster3DNavPreferences.representativesPerClusterProperty().set(b);
            cloudView.setRepresentativesPerCluster(b);
        });
        cloudView.setRepresentativesPerCluster(
                Cluster3DNavPreferences.representativesPerClusterProperty().get());

        // Background color: a custom color, or "Auto" to match the QuPath light/dark theme.
        ColorPicker bgPicker = new ColorPicker();
        bgPicker.setTooltip(new Tooltip("Background color of the 3D view. Uncheck \"Auto\" to choose a custom color."));
        CheckBox autoBg = new CheckBox("Auto (theme)");
        autoBg.setTooltip(new Tooltip("Match the QuPath light/dark theme background."));
        String bgPref = Cluster3DNavPreferences.backgroundColorProperty().get();
        boolean autoInit = bgPref == null || bgPref.isBlank();
        autoBg.setSelected(autoInit);
        bgPicker.setValue(parseColorOrDefault(bgPref));
        bgPicker.setDisable(autoInit);
        Runnable applyBg = () -> {
            boolean auto = autoBg.isSelected();
            bgPicker.setDisable(auto);
            if (auto) {
                Cluster3DNavPreferences.backgroundColorProperty().set("");
                cloudView.setBackgroundColor(null);
            } else {
                Color c = bgPicker.getValue();
                Cluster3DNavPreferences.backgroundColorProperty().set(toHexColor(c));
                cloudView.setBackgroundColor(c);
            }
        };
        autoBg.selectedProperty().addListener((o, a, b) -> applyBg.run());
        bgPicker.valueProperty().addListener((o, a, b) -> applyBg.run());
        // Apply the persisted background to the view on construction.
        cloudView.setBackgroundColor(autoInit ? null : parseColorOrDefault(bgPref));
        HBox bgRow = new HBox(8, new Label("Background"), bgPicker, autoBg);

        VBox content = new VBox(
                6,
                bgRow,
                labeledRow("Crop scale", cropScale),
                labeledRow("Point size", pointSize),
                depthCue,
                hoverPreview,
                tripod,
                outlines,
                labeledRow("Representative cells per cluster", repsPerCluster));
        content.setPadding(new Insets(6));
        TitledPane pane = new TitledPane("Display options", content);
        pane.setExpanded(false);
        pane.setAnimated(false);
        return pane;
    }

    private static HBox labeledRow(String label, Region control) {
        HBox row = new HBox(8, new Label(label), control);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    /** A short vertical divider between control groups in the top strip. */
    private static Separator verticalSeparator() {
        Separator s = new Separator(Orientation.VERTICAL);
        s.setPrefHeight(24);
        return s;
    }

    /** Parse a "#RRGGBB" web color; falls back to the light-theme default on empty/invalid. */
    private static Color parseColorOrDefault(String hex) {
        if (hex != null && !hex.isBlank()) {
            try {
                return Color.web(hex);
            } catch (IllegalArgumentException ignored) {
                // fall through to the default below
            }
        }
        return Color.web("#FAFAFA"); // matches the light-theme background default
    }

    /** Format an opaque JavaFX Color as an ASCII "#RRGGBB" web string. */
    private static String toHexColor(Color c) {
        return String.format(
                "#%02X%02X%02X",
                (int) Math.round(c.getRed() * 255), (int) Math.round(c.getGreen() * 255), (int)
                        Math.round(c.getBlue() * 255));
    }

    private static Spinner<Double> doubleSpinner(double min, double max, double value, double step) {
        Spinner<Double> s = new Spinner<>();
        SpinnerValueFactory.DoubleSpinnerValueFactory factory =
                new SpinnerValueFactory.DoubleSpinnerValueFactory(min, max, Math.max(min, Math.min(max, value)), step);
        s.setValueFactory(factory);
        s.setPrefWidth(90);
        // Let the user type a value, not just use the arrows.
        s.setEditable(true);
        // JavaFX editable Spinners only commit typed text on Enter, silently discarding
        // it on focus-loss. Commit (clamped) when focus leaves; revert on bad input.
        s.getEditor().focusedProperty().addListener((obs, hadFocus, hasFocus) -> {
            if (!hasFocus) {
                commitSpinnerText(s, min, max);
            }
        });
        s.getEditor().setOnAction(e -> commitSpinnerText(s, min, max));
        return s;
    }

    private static void commitSpinnerText(Spinner<Double> s, double min, double max) {
        String text = s.getEditor().getText();
        try {
            double v = Double.parseDouble(text.trim());
            v = Math.max(min, Math.min(max, v));
            s.getValueFactory().setValue(v);
            s.getEditor().setText(s.getValueFactory().getConverter().toString(v));
        } catch (NumberFormatException ex) {
            s.getEditor().setText(s.getValueFactory().getConverter().toString(s.getValue()));
        }
    }

    private static Spinner<Integer> intSpinner(int min, int max, int value) {
        return intSpinner(min, max, value, 1);
    }

    private static Spinner<Integer> intSpinner(int min, int max, int value, int step) {
        Spinner<Integer> s = new Spinner<>();
        s.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(
                min, max, Math.max(min, Math.min(max, value)), step));
        s.setPrefWidth(90);
        s.setEditable(true);
        // Editable Spinners only commit typed text on Enter; also commit (clamped) on focus-loss.
        s.getEditor().focusedProperty().addListener((obs, hadFocus, hasFocus) -> {
            if (!hasFocus) {
                commitIntSpinnerText(s, min, max);
            }
        });
        s.getEditor().setOnAction(e -> commitIntSpinnerText(s, min, max));
        return s;
    }

    private static void commitIntSpinnerText(Spinner<Integer> s, int min, int max) {
        String text = s.getEditor().getText();
        try {
            int v = Integer.parseInt(text.trim());
            v = Math.max(min, Math.min(max, v));
            s.getValueFactory().setValue(v);
            s.getEditor().setText(Integer.toString(v));
        } catch (NumberFormatException ex) {
            s.getEditor().setText(Integer.toString(s.getValue()));
        }
    }

    // ---------------- data flow ----------------

    private void onActiveImageChanged() {
        title.set("Cluster 3D Navigator - " + currentImageName());
        modeProject.setDisable(qupath.getProject() == null);
        if (hostMode || modeProject.isSelected()) {
            // Host-fixed scope OR project mode: the cloud does not re-read on an image switch.
            return;
        }
        // Only re-read when the active image ACTUALLY changed. imageDataProperty can fire
        // for the same image (e.g. metadata/selection churn); a needless reload would
        // clear the thumbnail cache and drop in-flight crops.
        if (qupath.getImageData() == lastReadImageData) {
            return;
        }
        reload();
    }

    private String currentImageName() {
        ImageData<BufferedImage> d = qupath.getImageData();
        if (d == null) {
            return "no image";
        }
        return d.getServer().getMetadata().getName();
    }

    private void setBusy(boolean busy, String message) {
        busyBox.setVisible(busy);
        busyLabel.setText(message);
        axisX.setDisable(busy);
        axisY.setDisable(busy);
        axisZ.setDisable(busy || twoD); // Z stays disabled in flat 2D
        modeCurrent.setDisable(busy);
        modeProject.setDisable(busy || qupath.getProject() == null);
    }

    /** Handle a 2D/3D radio toggle: persist the choice and re-read (2D builds differently). */
    private void onDimensionChanged() {
        if (suppressDimEvents) {
            return;
        }
        Cluster3DNavPreferences.dimensionsProperty().set(dim2D.isSelected() ? "2d" : "3d");
        reload();
    }

    /**
     * Apply the effective view dimensionality for the current data: 2D when the user
     * chose it OR when there are fewer than 3 numeric measurements (3D impossible). Updates
     * the radios (without firing), disables 3D when it cannot apply, toggles the Z controls,
     * and switches the view. Call before choosing axes / building the cloud.
     */
    private void applyDimensions(int numericCount) {
        boolean canDo3D = numericCount >= 3;
        boolean want2D = "2d".equals(Cluster3DNavPreferences.dimensionsProperty().get());
        twoD = want2D || !canDo3D;
        suppressDimEvents = true;
        (twoD ? dim2D : dim3D).setSelected(true);
        dim3D.setDisable(!canDo3D);
        suppressDimEvents = false;
        zLabel.setDisable(twoD);
        axisZ.setDisable(twoD);
        cloudView.setTwoD(twoD);
    }

    /** Prompt the user to pick a project-image subset; store it. Returns false if cancelled. */
    private boolean promptImageSelection() {
        Project<BufferedImage> project = qupath.getProject();
        if (project == null) {
            return false;
        }
        Optional<List<ProjectImageEntry<BufferedImage>>> res =
                ProjectImageSelector.showDialog(ownerWindow(), project, "Select project images", selectedEntries);
        if (res.isEmpty()) {
            return false; // cancelled -> leave the current selection untouched
        }
        selectedEntries = res.get();
        return true;
    }

    /** Handle a mode-radio toggle: prompt for a subset when switching to project mode. */
    private void onModeChanged() {
        if (suppressModeEvents) {
            return;
        }
        if (modeProject.isSelected()) {
            selectImagesButton.setDisable(false);
            if (!promptImageSelection()) {
                // Cancelled: revert to current-image mode (no half-state).
                suppressModeEvents = true;
                modeCurrent.setSelected(true);
                suppressModeEvents = false;
                selectImagesButton.setDisable(true);
                Cluster3DNavPreferences.modeProperty().set("current");
                reload();
                return;
            }
            Cluster3DNavPreferences.modeProperty().set("project");
        } else {
            selectImagesButton.setDisable(true);
            Cluster3DNavPreferences.modeProperty().set("current");
        }
        reload();
    }

    /** Read (off-thread) then rebuild the cloud for the current mode. */
    public void reload() {
        title.set("Cluster 3D Navigator - " + currentImageName());
        // Stale from a prior dataset until buildAndShow re-evaluates it.
        twoOfThreeWarn.setVisible(false);
        twoOfThreeWarn.setManaged(false);
        // In host mode the scope is fixed: read the injected entries if any, else the
        // current image -- never the interactive mode toggle, never a prompt.
        boolean hasHostEntries = selectedEntries != null && !selectedEntries.isEmpty();
        boolean projectMode = hostMode ? hasHostEntries : modeProject.isSelected();
        Project<BufferedImage> project = qupath.getProject();
        ImageData<BufferedImage> imageData = qupath.getImageData();
        // Track the image we are (re)reading so onActiveImageChanged can ignore
        // duplicate imageDataProperty fires for the same image.
        lastReadImageData = imageData;

        if (!projectMode && imageData == null && project == null) {
            data = null;
            cloudView.setData(null);
            legend.setData(null);
            cloudView.setEmptyMessage("No image open. Open an image with detections, then reopen this view.");
            partialReadError = false;
            updatePointsLabel();
            updateNotes();
            return;
        }

        // Interactive project mode reads only the user-selected subset (host mode with
        // no entries falls through to the current-image read below, never this message).
        if (!hostMode && projectMode && (selectedEntries == null || selectedEntries.isEmpty())) {
            data = null;
            cloudView.setData(null);
            legend.setData(null);
            cloudView.setEmptyMessage("No images selected. Click \"Select images...\" to choose project images.");
            partialReadError = false;
            updatePointsLabel();
            updateNotes();
            return;
        }

        String imageId = null;
        String imageName = null;
        if (imageData != null) {
            imageName = imageData.getServer().getMetadata().getName();
            if (project != null) {
                ProjectImageEntry<BufferedImage> entry = project.getEntry(imageData);
                if (entry != null) {
                    imageId = entry.getID();
                }
            }
        }

        final boolean pm = projectMode;
        final List<ProjectImageEntry<BufferedImage>> selEntries =
                selectedEntries == null ? List.of() : new java.util.ArrayList<>(selectedEntries);
        final ImageData<BufferedImage> imgData = imageData;
        final String fImageId = imageId;
        final String fImageName = imageName;
        // Per-image cell limit + seed from prefs; the guaranteed per-cluster minimum reuses
        // the representatives-per-cluster control (floored at 1 so no cluster is dropped).
        final DetectionReader.ReadOptions opts = new DetectionReader.ReadOptions(
                Cluster3DNavPreferences.cellLimitPerImageProperty().get(),
                Math.max(
                        1,
                        Cluster3DNavPreferences.representativesPerClusterProperty()
                                .get()),
                Cluster3DNavPreferences.subsampleSeedProperty().get());

        setBusy(true, pm ? "Reading detections across selected images..." : "Reading detections...");
        cloudView.setEmptyMessage(null);
        Thread t = new Thread(
                () -> {
                    DetectionReader.ReadResult result;
                    try {
                        if (pm) {
                            result = DetectionReader.readEntries(
                                    selEntries, msg -> Platform.runLater(() -> busyLabel.setText(msg)), opts);
                        } else {
                            result = DetectionReader.readImage(imgData, fImageId, fImageName, opts);
                        }
                    } catch (Exception e) {
                        logger.error("Detection read failed", e);
                        result = new DetectionReader.ReadResult(List.of(), List.of(), true);
                    }
                    final DetectionReader.ReadResult fr = result;
                    Platform.runLater(() -> {
                        setBusy(false, "");
                        onReadComplete(fr);
                    });
                },
                "cluster3d-read");
        t.setDaemon(true);
        t.start();
    }

    private void onReadComplete(DetectionReader.ReadResult result) {
        this.numericAxes = result.numericAxes;
        this.partialReadError = result.readError;
        this.limited = result.limited;
        List<String> numeric = result.numericAxes;

        // Populate axis combos.
        suppressAxisEvents = true;
        axisX.getItems().setAll(numeric);
        axisY.getItems().setAll(numeric);
        axisZ.getItems().setAll(numeric);
        suppressAxisEvents = false;

        // Distinguish a genuine "no detections" empty state from a read FAILURE.
        if (result.records.isEmpty() && result.readError) {
            clearCloud("Could not read detections (see log).");
            return;
        }
        if (result.records.isEmpty()) {
            clearCloud("No detections in this image. Run a detection or clustering step first.");
            return;
        }
        if (numeric.size() < 2) {
            clearCloud("Need at least 2 numeric measurements to plot. This image has " + numeric.size() + ".");
            return;
        }

        // Decide 2D vs 3D for this data (forces 2D when < 3 numeric measurements) and
        // wire the view + Z controls accordingly before choosing axes.
        applyDimensions(numeric.size());

        // Choose axes: explicit user request, else remembered per project, else auto-detect.
        // Build from the read result, then DROP it: the per-cell measurement maps are not
        // retained -- only the built PointCloudData stays alive for the session.
        if (twoD) {
            String[] chosen = chooseAxes2D(numeric);
            suppressAxisEvents = true;
            axisX.setValue(chosen[0]);
            axisY.setValue(chosen[1]);
            suppressAxisEvents = false;
            buildAndShow(result.records, chosen[0], chosen[1], null);
        } else {
            String[] chosen = chooseAxes(numeric);
            suppressAxisEvents = true;
            axisX.setValue(chosen[0]);
            axisY.setValue(chosen[1]);
            axisZ.setValue(chosen[2]);
            suppressAxisEvents = false;
            buildAndShow(result.records, chosen[0], chosen[1], chosen[2]);
        }
    }

    private void clearCloud(String message) {
        data = null;
        resetPreview();
        cloudView.setData(null);
        legend.setData(null);
        cloudView.setEmptyMessage(message);
        autoTag.setText("");
        twoOfThreeWarn.setVisible(false);
        twoOfThreeWarn.setManaged(false);
        updatePointsLabel();
        updateNotes();
    }

    /** Clear the Cell preview and disable "Update from viewer" (indices are stale after a rebuild). */
    private void resetPreview() {
        lastPreviewIndex = -1;
        updateFromViewerButton.setDisable(true);
        previewView.setImage(null);
        previewCaption.setText("");
    }

    /** Build + show the cloud for the chosen axes. {@code z == null} builds a flat 2D cloud. */
    private void buildAndShow(List<DetectionReader.CellRecord> records, String x, String y, String z) {
        resetPreview();
        data = (z == null)
                ? DetectionReader.buildPointCloud2D(records, x, y)
                : DetectionReader.buildPointCloud(records, x, y, z);
        if (data.size() == 0) {
            data = null;
            cloudView.setData(null);
            legend.setData(null);
            cloudView.setEmptyMessage(z == null
                    ? "No cells have finite values on both chosen axes."
                    : "No cells have finite values on all three chosen axes.");
            updatePointsLabel();
            updateNotes();
            return;
        }
        cloudView.setEmptyMessage(null);
        cloudView.setData(data);
        legend.setData(data);
        cloudView.setVisibleMask(legend.getVisibleMask());
        Cluster3DNavPreferences.setLastAxes(projectKey(), x, y, z == null ? "" : z);
        updateTwoOfThreeWarning(x, y);
        updatePointsLabel();
        updateNotes();
    }

    /** Show/hide the "2 axes of a 3D embedding" warning for the current 2D axis choice. */
    private void updateTwoOfThreeWarning(String x, String y) {
        boolean warn = twoD && AxisAutoDetect.isTwoOfThree(numericAxes, x, y);
        twoOfThreeWarn.setText(warn
                ? "Warning: X and Y are two components of a 3D embedding. A 2D view of a 3D embedding is "
                        + "not a true 2D embedding -- for a correct 2D view, compute a 2D embedding (e.g. a 2D UMAP)."
                : "");
        twoOfThreeWarn.setStyle(ThemeUtils.warningStyle(ThemeUtils.isDark(getScene())));
        twoOfThreeWarn.setVisible(warn);
        twoOfThreeWarn.setManaged(warn);
    }

    private String[] chooseAxes(List<String> numeric) {
        if (isValid(requestedAxes, numeric)) {
            autoTag.setText("");
            return requestedAxes;
        }
        String[] remembered = Cluster3DNavPreferences.getLastAxes(projectKey());
        if (isValid(remembered, numeric)) {
            autoTag.setText("");
            return remembered;
        }
        List<String> triple = AxisAutoDetect.detect(numeric);
        if (triple.size() == 3) {
            autoTag.setText("(auto-detected: " + AxisAutoDetect.detectedFamily(numeric) + ")");
            return triple.toArray(new String[0]);
        }
        autoTag.setText("(no embedding detected -- pick 3 axes)");
        return new String[] {numeric.get(0), numeric.get(1), numeric.get(2)};
    }

    /** Choose the 2 axes for a flat 2D view: explicit request, else remembered, else auto-detect. */
    private String[] chooseAxes2D(List<String> numeric) {
        if (isValid2D(requestedAxes, numeric)) {
            autoTag.setText("");
            return new String[] {requestedAxes[0], requestedAxes[1]};
        }
        String[] remembered = Cluster3DNavPreferences.getLastAxes(projectKey());
        if (isValid2D(remembered, numeric)) {
            autoTag.setText("");
            return new String[] {remembered[0], remembered[1]};
        }
        List<String> pair = AxisAutoDetect.detectPair(numeric);
        if (pair.size() == 2) {
            autoTag.setText("(auto-detected: " + AxisAutoDetect.detectedFamilyPair(numeric) + ")");
            return pair.toArray(new String[0]);
        }
        autoTag.setText("(no 2D embedding detected -- pick 2 axes)");
        return new String[] {numeric.get(0), numeric.get(1)};
    }

    private static boolean isValid(String[] axes, List<String> numeric) {
        if (axes == null || axes.length != 3) {
            return false;
        }
        for (String a : axes) {
            if (a == null || a.isBlank() || !numeric.contains(a)) {
                return false;
            }
        }
        return true;
    }

    /** True if the first two entries of {@code axes} are distinct-enough, non-blank, present columns. */
    private static boolean isValid2D(String[] axes, List<String> numeric) {
        if (axes == null || axes.length < 2) {
            return false;
        }
        for (int i = 0; i < 2; i++) {
            if (axes[i] == null || axes[i].isBlank() || !numeric.contains(axes[i])) {
                return false;
            }
        }
        return true;
    }

    private void onAxisComboChanged() {
        if (suppressAxisEvents) {
            return;
        }
        String x = axisX.getValue();
        String y = axisY.getValue();
        if (x == null || y == null) {
            return;
        }
        // The measurement maps are not retained, so a new axis choice re-reads.
        autoTag.setText("");
        if (twoD) {
            requestedAxes = new String[] {x, y};
            reload();
            return;
        }
        String z = axisZ.getValue();
        if (z == null) {
            return;
        }
        requestedAxes = new String[] {x, y, z};
        reload();
    }

    private void onLegendChanged() {
        cloudView.setVisibleMask(legend.getVisibleMask());
        updatePointsLabel();
    }

    private void updatePointsLabel() {
        if (data == null) {
            pointsLabel.setText("Points: 0 / 0");
            return;
        }
        int survivors = data.size();
        int total = data.totalConsidered(); // survivors + omitted
        boolean[] mask = legend.getVisibleMask();
        int shown = 0;
        for (int i = 0; i < survivors; i++) {
            int ci = data.classIdx[i];
            if (ci >= 0 && ci < mask.length && mask[ci]) {
                shown++;
            }
        }
        String text = String.format("Points: %,d shown / %,d total", shown, total);
        if (data.omittedCount > 0) {
            text += String.format(" (%,d omitted: missing axis value)", data.omittedCount);
        }
        if (limited) {
            text += " (limited)";
        }
        pointsLabel.setText(text);
    }

    /** Bottom-bar note for omitted cells and/or a partial project read failure. */
    private void updateNotes() {
        java.util.List<String> parts = new java.util.ArrayList<>();
        if (data != null && data.omittedCount > 0) {
            parts.add(String.format(
                    "%,d cell(s) omitted: missing a value on one of the chosen axes.", data.omittedCount));
        }
        if (partialReadError) {
            parts.add("Some images could not be read (see log).");
        }
        boolean show = !parts.isEmpty();
        noteLabel.setText(show ? String.join("  ", parts) : "");
        noteLabel.setVisible(show);
        noteLabel.setManaged(show);
        noteLabel.setStyle(ThemeUtils.warningStyle(ThemeUtils.isDark(getScene())));
    }

    private void openAxisPicker() {
        int need = twoD ? 2 : 3;
        if (numericAxes == null || numericAxes.size() < need) {
            return;
        }
        String[] remembered = Cluster3DNavPreferences.getLastAxes(projectKey());
        boolean rememberInit = twoD ? isValid2D(remembered, numericAxes) : isValid(remembered, numericAxes);
        Optional<AxisPickerDialog.Result> res = AxisPickerDialog.show(
                ownerWindow(),
                numericAxes,
                axisX.getValue(),
                axisY.getValue(),
                axisZ.getValue(),
                rememberInit,
                twoD);
        res.ifPresent(r -> {
            autoTag.setText("");
            requestedAxes = twoD ? new String[] {r.x, r.y} : new String[] {r.x, r.y, r.z};
            if (r.remember) {
                Cluster3DNavPreferences.setLastAxes(projectKey(), r.x, r.y, twoD ? "" : r.z);
            }
            // Re-read (measurement maps are not retained) and rebuild for the new axes.
            reload();
        });
    }

    private String projectKey() {
        Project<BufferedImage> project = qupath.getProject();
        if (project == null || project.getPath() == null) {
            return null;
        }
        return project.getPath().toString();
    }

    // ---------------- preview ----------------

    private void loadPreview(int index) {
        if (data == null || index < 0 || index >= data.refs.length) {
            return;
        }
        CellRef ref = data.refs[index];
        if (ref == null) {
            return;
        }
        // Remember the previewed cell so "Update from viewer" can re-render it.
        lastPreviewIndex = index;
        updateFromViewerButton.setDisable(false);
        final long token = ++previewToken;
        final double scale = Cluster3DNavPreferences.cropScaleProperty().get();
        previewCaption.setText("Loading crop...");
        Thread t = new Thread(
                () -> {
                    BufferedImage crop = cropService.readCrop(ref, scale);
                    Image fx = crop != null ? SwingFXUtils.toFXImage(crop, null) : null;
                    Platform.runLater(() -> {
                        if (token != previewToken) {
                            return;
                        }
                        if (fx != null) {
                            previewView.setImage(fx);
                            previewCaption.setText(data.classDisplayName(data.classIdx[index]));
                        } else {
                            previewView.setImage(null);
                            previewCaption.setText("Crop unavailable");
                        }
                    });
                },
                "cluster3d-crop");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Re-render the cell preview (and any in-cloud thumbnails) using the viewer's
     * current display settings. The point cloud positions/colors are unaffected.
     */
    private void refreshPreviewFromViewer() {
        if (data == null || lastPreviewIndex < 0 || lastPreviewIndex >= data.refs.length) {
            return;
        }
        cropService.clearDisplayCache();
        cloudView.clearImageCache();
        loadPreview(lastPreviewIndex);
    }

    // ---------------- lifecycle ----------------

    /** Remove QuPath listeners, release cached crop servers, and stop the thumbnail loader. */
    public void dispose() {
        logger.info("Disposing Cluster 3D Navigator pane; removing listeners and releasing crop servers");
        qupath.imageDataProperty().removeListener(imageListener);
        qupath.projectProperty().removeListener(projectListener);
        cloudView.clearImageCache();
        cloudView.shutdown();
        try {
            cropService.close();
        } catch (Exception e) {
            logger.debug("Error closing crop service: {}", e.getMessage());
        }
    }
}
