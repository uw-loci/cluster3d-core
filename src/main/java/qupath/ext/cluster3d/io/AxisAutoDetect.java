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
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure axis auto-detection: given a set of measurement names, find a complete
 * 3-component embedding triple (UMAP / PCA / PC / tSNE), component 1/2/3, in
 * that order of preference.
 *
 * <p>Matching is case-insensitive and tolerant of separators between the base
 * name and the component number: {@code UMAP1}, {@code UMAP_1}, {@code UMAP-1},
 * and {@code "UMAP 1"} all match. Returns the three original measurement names
 * (X=component 1, Y=2, Z=3) or an empty list when no complete triple is found.</p>
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

    /**
     * Detect an embedding triple among the given measurement names.
     *
     * @param names measurement names (any order, any case)
     * @return the three names {X, Y, Z}, or an empty list if none complete
     */
    public static List<String> detect(Collection<String> names) {
        if (names == null || names.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> nameList = new ArrayList<>(names);
        for (String[] family : FAMILIES) {
            List<String> triple = findTriple(nameList, family[1]);
            if (triple != null) {
                return triple;
            }
        }
        return Collections.emptyList();
    }

    /** Family label ("UMAP", "PCA", ...) of the triple {@link #detect} would return, or null. */
    public static String detectedFamily(Collection<String> names) {
        if (names == null || names.isEmpty()) {
            return null;
        }
        List<String> nameList = new ArrayList<>(names);
        for (String[] family : FAMILIES) {
            if (findTriple(nameList, family[1]) != null) {
                return family[0];
            }
        }
        return null;
    }

    private static List<String> findTriple(List<String> names, String baseRegex) {
        String[] found = new String[3];
        for (int comp = 1; comp <= 3; comp++) {
            Pattern p =
                    Pattern.compile("^\\s*" + baseRegex + "[_\\-\\s]?0*" + comp + "\\s*$", Pattern.CASE_INSENSITIVE);
            for (String name : names) {
                if (name == null) {
                    continue;
                }
                Matcher m = p.matcher(name);
                if (m.matches()) {
                    found[comp - 1] = name;
                    break;
                }
            }
            if (found[comp - 1] == null) {
                return null;
            }
        }
        List<String> triple = new ArrayList<>(3);
        triple.add(found[0]);
        triple.add(found[1]);
        triple.add(found[2]);
        return triple;
    }
}
