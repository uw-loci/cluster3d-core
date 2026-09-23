package qupath.ext.cluster3d.io;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link AxisChoice} precedence.
 * <p>
 * The bug these pin: a run whose embedding columns were named "UMAP_Demo1..3" was not
 * recognised by {@link AxisAutoDetect}, so the view opened on the first three
 * measurements -- morphology columns -- after a read that takes minutes. The host knows
 * the column names it just wrote, so it can say, whatever they are called.
 */
class AxisChoiceTest {

    private static final List<String> NUMERIC =
            List.of("Nucleus: Area", "Nucleus: Length", "Nucleus: Circularity", "UMAP_Demo1", "UMAP_Demo2",
                    "UMAP_Demo3");

    private static final String[] HOST = {"UMAP_Demo1", "UMAP_Demo2", "UMAP_Demo3"};

    @Test
    void hostAxesWinWhenNothingWasRequested() {
        AxisChoice.Result r = AxisChoice.choose(3, null, HOST, null, List.of(), NUMERIC);
        assertArrayEquals(HOST, r.axes());
        assertEquals(AxisChoice.Source.HOST, r.source());
    }

    @Test
    void anUnrecognisedEmbeddingNameNoLongerFallsBackToTheFirstMeasurements() {
        // AxisAutoDetect cannot parse this family, so it contributes nothing.
        assertEquals(List.of(), AxisAutoDetect.detect(NUMERIC));
        AxisChoice.Result r = AxisChoice.choose(3, null, HOST, null, AxisAutoDetect.detect(NUMERIC), NUMERIC);
        assertArrayEquals(HOST, r.axes());
    }

    @Test
    void theUsersInSessionPickBeatsTheHost() {
        String[] requested = {"Nucleus: Area", "Nucleus: Length", "Nucleus: Circularity"};
        AxisChoice.Result r = AxisChoice.choose(3, requested, HOST, null, List.of(), NUMERIC);
        assertArrayEquals(requested, r.axes());
        assertEquals(AxisChoice.Source.REQUESTED, r.source());
    }

    @Test
    void theHostBeatsARememberedPick() {
        String[] remembered = {"Nucleus: Area", "Nucleus: Length", "Nucleus: Circularity"};
        AxisChoice.Result r = AxisChoice.choose(3, null, HOST, remembered, List.of(), NUMERIC);
        assertArrayEquals(HOST, r.axes(), "a result window should open on its own run's embedding");
    }

    @Test
    void aRememberedPickIsStillUsedWhenNoHostSaysOtherwise() {
        String[] remembered = {"Nucleus: Area", "Nucleus: Length", "Nucleus: Circularity"};
        AxisChoice.Result r = AxisChoice.choose(3, null, null, remembered, List.of(), NUMERIC);
        assertArrayEquals(remembered, r.axes());
        assertEquals(AxisChoice.Source.REMEMBERED, r.source());
    }

    @Test
    void autoDetectionStillWinsOverTheFallback() {
        List<String> names = List.of("Area", "Length", "Circularity", "UMAP1", "UMAP2", "UMAP3");
        AxisChoice.Result r = AxisChoice.choose(3, null, null, null, AxisAutoDetect.detect(names), names);
        assertArrayEquals(new String[] {"UMAP1", "UMAP2", "UMAP3"}, r.axes());
        assertEquals(AxisChoice.Source.AUTO_DETECTED, r.source());
    }

    @Test
    void staleNamesAreIgnoredAtEveryLevel() {
        String[] gone = {"UMAP_Old1", "UMAP_Old2", "UMAP_Old3"};
        AxisChoice.Result r = AxisChoice.choose(3, gone, gone, gone, List.of(), NUMERIC);
        assertEquals(AxisChoice.Source.FALLBACK, r.source());
        assertArrayEquals(new String[] {"Nucleus: Area", "Nucleus: Length", "Nucleus: Circularity"}, r.axes());
    }

    @Test
    void repeatedNamesAreRejectedRatherThanCollapsingTheCloudToALine() {
        String[] dup = {"UMAP_Demo1", "UMAP_Demo1", "UMAP_Demo2"};
        AxisChoice.Result r = AxisChoice.choose(3, dup, null, null, List.of(), NUMERIC);
        assertEquals(AxisChoice.Source.FALLBACK, r.source());
    }

    @Test
    void twoDTakesTheFirstTwoHostAxes() {
        AxisChoice.Result r = AxisChoice.choose(2, null, HOST, null, List.of(), NUMERIC);
        assertArrayEquals(new String[] {"UMAP_Demo1", "UMAP_Demo2"}, r.axes());
        assertEquals(AxisChoice.Source.HOST, r.source());
    }

    @Test
    void tooFewMeasurementsIsAnError() {
        assertThrows(
                IllegalArgumentException.class, () -> AxisChoice.choose(3, null, null, null, null, List.of("A", "B")));
    }
}
