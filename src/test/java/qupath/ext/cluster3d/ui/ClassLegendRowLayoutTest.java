package qupath.ext.cluster3d.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import qupath.ext.cluster3d.model.PointCloudData;
import qupath.lib.objects.classes.PathClass;

/**
 * A long class name must not push the per-class count out of the legend.
 *
 * <p>The right panel is 260 px, so a legend row gives the name about 160. A
 * host that namespaces its classes fills that on its own: QP-CAT's
 * {@code SavedResultApplier} prefixes every applied class with the result name,
 * so after an apply all seven rows read {@code auto_20260927_015057...} with the
 * cluster number AND the count clipped off the right -- the panel at its least
 * readable immediately after the action that fills it.
 *
 * <p>The count is what tells you an apply landed on the number of cells you
 * expected, so it is the one thing that may not be squeezed.
 */
class ClassLegendRowLayoutTest {

    /** Width of the right panel the legend sits in (Cluster3DNavigatorPane). */
    private static final double PANEL_WIDTH = 260;

    private static final String LONG_NAME =
            "auto_20260927_015057_hdbscan: Cluster 0";

    private static boolean fxUp;

    @BeforeAll
    static void startFx() {
        CountDownLatch latch = new CountDownLatch(1);
        try {
            Platform.startup(latch::countDown);
        } catch (IllegalStateException alreadyRunning) {
            latch.countDown();
        } catch (UnsupportedOperationException | Error noDisplay) {
            // Headless box: this is a layout question and there is nothing to
            // lay out. Skip rather than fail -- see the assumption below.
            return;
        }
        try {
            fxUp = latch.await(60, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        Platform.setImplicitExit(false);
    }

    private static PointCloudData data(String... classNames) {
        int n = classNames.length;
        PathClass[] classes = new PathClass[n];
        Color[] palette = new Color[n];
        int[] counts = new int[n];
        for (int i = 0; i < n; i++) {
            classes[i] = PathClass.fromString(classNames[i]);
            palette[i] = Color.CORNFLOWERBLUE;
            counts[i] = 1000 + i;      // 4 digits, as a real run produces
        }
        return new PointCloudData(
                new float[0], new float[0], new float[0],
                new double[0], new double[0], new double[0],
                new int[0], List.of(classes), palette, counts,
                null, "x", "y", "z", 0);
    }

    /** The legend, laid out at the width it really gets. */
    private static ClassLegend laidOut(PointCloudData d) throws Exception {
        return onFx(() -> {
            ClassLegend legend = new ClassLegend();
            legend.setData(d);
            legend.setPrefWidth(PANEL_WIDTH);
            legend.setMaxWidth(PANEL_WIDTH);
            VBox root = new VBox(legend);
            new Scene(root, PANEL_WIDTH, 600);
            root.applyCss();
            root.layout();
            return legend;
        });
    }

    private static List<Node> rowsOf(ClassLegend legend) {
        for (Node child : legend.getChildrenUnmodifiable()) {
            if (child instanceof ScrollPane sp && sp.getContent() instanceof VBox box) {
                return box.getChildrenUnmodifiable();
            }
        }
        throw new AssertionError("no rows container in the legend");
    }

    // Found by ROLE, not child index, so the test fails on the clipping it is
    // about rather than on a cast when the row's children change.
    private static List<Label> labelsOf(HBox row) {
        return row.getChildren().stream()
                .filter(Label.class::isInstance)
                .map(Label.class::cast)
                .toList();
    }

    private static Label nameOf(HBox row) {
        return labelsOf(row).get(0);
    }

    private static Label countOf(HBox row) {
        List<Label> labels = labelsOf(row);
        return labels.get(labels.size() - 1);
    }

    @Test
    void theCountSurvivesAClassNameFarWiderThanTheColumn() throws Exception {
        Assumptions.assumeTrue(fxUp, "JavaFX did not start (no display)");

        ClassLegend legend = laidOut(data(LONG_NAME, LONG_NAME.replace("0", "1")));
        for (Node node : rowsOf(legend)) {
            HBox row = (HBox) node;
            Label count = countOf(row);
            Label name = nameOf(row);

            // The count is drawn at its FULL natural width. This is the
            // assertion that matters, and the one that is easy to get wrong:
            // the count never left the row's bounds, so checking maxX proves
            // nothing. What happened instead is that HBox shrinks every
            // shrinkable child proportionally, so a 4-digit count came out at
            // 13 px against a preferred 33 -- present, and rendered as an
            // ellipsis. Measured on the pre-fix layout at this exact width.
            assertThat(count.getWidth())
                    .as("count '%s' must be drawn at its natural width, not squeezed",
                            count.getText())
                    .isGreaterThanOrEqualTo(count.prefWidth(-1) - 0.5);
            assertThat(count.getBoundsInParent().getMaxX())
                    .as("count's right edge must stay inside the row")
                    .isLessThanOrEqualTo(row.getWidth() + 0.5);

            // ...which it can only do because the NAME took the whole squeeze.
            assertThat(name.getWidth())
                    .as("the name should have been compressed, not the count")
                    .isLessThan(name.prefWidth(-1));
        }
    }

    @Test
    void theNameElidesFromTheLeftSoTheDistinguishingTailSurvives() throws Exception {
        Assumptions.assumeTrue(fxUp, "JavaFX did not start (no display)");

        ClassLegend legend = laidOut(data(LONG_NAME));
        Label name = nameOf((HBox) rowsOf(legend).get(0));

        // Trailing ellipsis would leave every row reading "auto_2026...",
        // which is the defect: the namespace is the part they share.
        assertThat(name.getTextOverrun()).isEqualTo(OverrunStyle.LEADING_ELLIPSIS);
        // Nothing is hidden outright -- the full name is one hover away.
        assertThat(name.getTooltip()).isNotNull();
        assertThat(name.getTooltip().getText()).isEqualTo(LONG_NAME);
    }

    @Test
    void aShortNameStillLeavesTheCountHardRight() throws Exception {
        Assumptions.assumeTrue(fxUp, "JavaFX did not start (no display)");

        // Removing the spacer must not left-shunt the count on normal names.
        ClassLegend legend = laidOut(data("Cluster 0", "Cluster 1"));
        for (Node node : rowsOf(legend)) {
            HBox row = (HBox) node;
            Label count = countOf(row);
            assertThat(count.getBoundsInParent().getMaxX())
                    .as("count should sit at the right edge")
                    .isCloseTo(row.getWidth(), org.assertj.core.data.Offset.offset(1.0));
        }
    }

    private static <T> T onFx(Callable<T> work) throws Exception {
        FutureTask<T> task = new FutureTask<>(work);
        Platform.runLater(task);
        return task.get(60, TimeUnit.SECONDS);
    }
}
