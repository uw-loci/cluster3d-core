package qupath.ext.cluster3d.ui;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * Changing an axis measurement re-reads the detections and rebuilds the legend.
 * That rebuild used to turn every class back on, so narrowing to two classes and
 * then viewing them against a different measurement -- the normal way to use this
 * view -- silently put all of them back.
 */
class ClassLegendVisibilityTest {

    private static Map<String, Boolean> remembered(Object... pairs) {
        Map<String, Boolean> m = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            m.put((String) pairs[i], (Boolean) pairs[i + 1]);
        }
        return m;
    }

    @Test
    void anUnseenClassStartsVisible() {
        assertArrayEquals(new boolean[] {true, true},
                ClassLegend.restoreVisibility(new String[] {"A", "B"}, remembered()));
    }

    @Test
    void hiddenClassesStayHiddenAcrossAnAxisChange() {
        assertArrayEquals(new boolean[] {true, false, true},
                ClassLegend.restoreVisibility(new String[] {"A", "B", "C"},
                        remembered("A", true, "B", false, "C", true)));
    }

    @Test
    void stateFollowsTheNameNotThePosition() {
        // The same classes, reordered: B must still be the hidden one.
        assertArrayEquals(new boolean[] {false, true, true},
                ClassLegend.restoreVisibility(new String[] {"B", "C", "A"},
                        remembered("A", true, "B", false, "C", true)));
    }

    @Test
    void aNewClassIsVisibleBesideRememberedOnes() {
        assertArrayEquals(new boolean[] {false, true},
                ClassLegend.restoreVisibility(new String[] {"B", "D"},
                        remembered("A", true, "B", false)));
    }

    @Test
    void everythingComesBackWhenTheMemoryWouldHideEveryClass() {
        // Otherwise the cloud is empty after the axis change with nothing to click.
        assertArrayEquals(new boolean[] {true, true},
                ClassLegend.restoreVisibility(new String[] {"A", "B"},
                        remembered("A", false, "B", false)));
    }
}
