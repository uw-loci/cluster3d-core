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

package qupath.ext.cluster3d.io;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure axis auto-detection: given a set of measurement names, find a complete
 * embedding tuple (UMAP / PCA / PC / tSNE), component 1/2[/3], in that order of
 * preference. {@link #detect} finds a 3-component triple (for the 3D view);
 * {@link #detectPair} finds a 2-component pair (for the flat 2D view).
 *
 * <p>Matching is case-insensitive and tolerant of separators between the base
 * name and the component number: {@code UMAP1}, {@code UMAP_1}, {@code UMAP-1},
 * and {@code "UMAP 1"} all match. Returns the original measurement names
 * (X=component 1, Y=2, Z=3) or an empty list when no complete tuple is found.</p>
 */
public final class AxisAutoDetect {

    private AxisAutoDetect() {}

    // Base regexes for each embedding family. A family matches a name of the form
    // BASE + optional separator + a single component digit, anchored end-to-end.
    // Order matters: UMAP is preferred, then PCA, then PC, then tSNE. PCA is tried
    // before PC so "PCA1" is not mis-classified by the shorter "PC" base.
    private static final String[][] FAMILIES = {
        {"UMAP", "u[_\\-\\s]?map"},
        {"PCA", "pca"},
        {"PC", "pc"},
        {"tSNE", "t[_\\-\\s]?sne"},
    };

    // A writer may put anything in front of the family token, and two things
    // routinely do: a tool marker ("QPCAT UMAP1", so QP-CAT's output is
    // distinguishable from a user's own measurements) and a RUN NAME. QP-CAT
    // defaults the embedding name to include the dimensionality, so a 3D run
    // writes "3DUMAP1" -- and a prefix rule that only knew about "qpcat " failed
    // to recognise that as UMAP at all, leaving the axis row reading "no
    // embedding detected" beside three correctly-chosen UMAP axes.
    //
    // Bounded and conservative: letters, digits, underscore, hyphen and space
    // only, at most 16 characters, and lazy so the LAST family occurrence in a
    // name wins. The family token and its component digit must still close the
    // name, which is what keeps "Nucleus: Area" and "QPCAT CN 3" out.
    // A free-form prefix is too permissive: "QPCAT" CONTAINS "PCA", so
    // "QPCAT CN 1" parsed as PCA component 1. The prefix must therefore end at a
    // separator, or be a dimensionality tag ("2D", "3D") written flush against
    // the family, which is the form QP-CAT's default embedding name produces.
    private static final String OPTIONAL_TOOL_PREFIX = "(?:[A-Za-z0-9_\\-\\s]{0,16}[_\\-\\s]|[0-9]{1,2}[dD])?";

    // ...and a run name can also land BETWEEN the family and the component digit,
    // because QP-CAT lets you name an embedding whatever you like: "UMAP_Demo"
    // writes "UMAP_Demo1". Same bounded character class.
    //
    // A false positive here costs a wrong pre-selection in three dropdowns the
    // user can change; a false negative costs the axis row telling them no
    // embedding exists when it does. The trade is deliberately in this direction.
    private static final String OPTIONAL_RUN_NAME = "(?:[A-Za-z0-9_\\-\\s]{0,16}?)";

    /**
     * Detect an embedding triple among the given measurement names.
     *
     * @param names measurement names (any order, any case)
     * @return the three names {X, Y, Z}, or an empty list if none complete
     */
    public static List<String> detect(Collection<String> names) {
        return detectComponents(names, 3);
    }

    /**
     * Detect a 2-component embedding pair (X=component 1, Y=2) for the flat 2D view,
     * or an empty list if no complete pair is found.
     */
    public static List<String> detectPair(Collection<String> names) {
        return detectComponents(names, 2);
    }

    /** Detect the first {@code count}-component embedding tuple, or an empty list. */
    private static List<String> detectComponents(Collection<String> names, int count) {
        if (names == null || names.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> nameList = new ArrayList<>(names);
        for (String[] family : FAMILIES) {
            List<String> tuple = findComponents(nameList, family[1], count);
            if (tuple != null) {
                return tuple;
            }
        }
        return Collections.emptyList();
    }

    /** Family label ("UMAP", "PCA", ...) of the triple {@link #detect} would return, or null. */
    public static String detectedFamily(Collection<String> names) {
        return detectedFamily(names, 3);
    }

    /** Family label of the pair {@link #detectPair} would return, or null. */
    public static String detectedFamilyPair(Collection<String> names) {
        return detectedFamily(names, 2);
    }

    private static String detectedFamily(Collection<String> names, int count) {
        if (names == null || names.isEmpty()) {
            return null;
        }
        List<String> nameList = new ArrayList<>(names);
        for (String[] family : FAMILIES) {
            if (findComponents(nameList, family[1], count) != null) {
                return family[0];
            }
        }
        return null;
    }

    /**
     * Scientific-correctness guard for the 2D view (PURE): true if the chosen X and Y are
     * two components of the SAME embedding family AND a THIRD component of that family is
     * present among {@code names}. That signals the user is plotting two axes of a 3D
     * embedding -- which is NOT a true 2D embedding (a 2D UMAP is optimized for two
     * dimensions and will not match any 2-axis slice of a 3D UMAP).
     */
    public static boolean isTwoOfThree(Collection<String> names, String xName, String yName) {
        if (names == null || xName == null || yName == null) {
            return false;
        }
        String[] px = parseComponent(xName);
        String[] py = parseComponent(yName);
        if (px == null || py == null || !px[0].equals(py[0]) || !px[2].equals(py[2])) {
            return false; // not both components of one embedding
        }
        // A third-or-higher component of this embedding present -> it is >= 3D. A 3D
        // embedding stored beside a 2D one does not make the 2D one a slice.
        for (String name : names) {
            if (name == null) {
                continue;
            }
            String[] pc = parseComponent(name);
            if (pc != null && pc[0].equals(px[0]) && pc[2].equals(px[2]) && Integer.parseInt(pc[1]) >= 3) {
                return true;
            }
        }
        return false;
    }

    /**
     * Parse a name into {familyLabel, componentDigit, embeddingKey} (e.g. {"UMAP", "2",
     * "qpcat2dumap"}), or null if it is not one. Names share an embedding key only when
     * everything but the component number matches, ignoring case and separators.
     */
    static String[] parseComponent(String name) {
        if (name == null) {
            return null;
        }
        for (String[] family : FAMILIES) {
            String[] parsed = parse(name, family[1]);
            if (parsed != null) {
                return new String[] {family[0], parsed[1], parsed[0]};
            }
        }
        return null;
    }

    /** {embeddingKey, componentDigit} for a name of one family, or null. */
    private static String[] parse(String name, String baseRegex) {
        Pattern p = Pattern.compile(
                "^\\s*(" + OPTIONAL_TOOL_PREFIX + baseRegex + OPTIONAL_RUN_NAME + ")[_\\-\\s]?0*([0-9]+)\\s*$",
                Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(name);
        if (!m.matches()) {
            return null;
        }
        String key = m.group(1).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return new String[] {key, String.valueOf(Integer.parseInt(m.group(2)))};
    }

    /**
     * Components 1..count of ONE embedding of a family, or null when no embedding has
     * them all. Names are grouped by embedding first, so "QPCAT 2D UMAP1" and
     * "QPCAT 3D UMAP3" are never paired. An embedding with exactly {@code count}
     * components is preferred (a 3D run for the 3D view, a 2D run for the 2D view);
     * otherwise the first complete one, in the order the names came.
     */
    private static List<String> findComponents(List<String> names, String baseRegex, int count) {
        Map<String, Map<Integer, String>> embeddings = new LinkedHashMap<>();
        for (String name : names) {
            if (name == null) {
                continue;
            }
            String[] parsed = parse(name, baseRegex);
            if (parsed != null) {
                embeddings
                        .computeIfAbsent(parsed[0], k -> new HashMap<>())
                        .putIfAbsent(Integer.parseInt(parsed[1]), name);
            }
        }
        List<String> fallback = null;
        for (Map<Integer, String> components : embeddings.values()) {
            List<String> tuple = new ArrayList<>(count);
            for (int comp = 1; comp <= count && components.containsKey(comp); comp++) {
                tuple.add(components.get(comp));
            }
            if (tuple.size() < count) {
                continue;
            }
            if (Collections.max(components.keySet()) == count) {
                return tuple;
            }
            if (fallback == null) {
                fallback = tuple;
            }
        }
        return fallback;
    }
}
