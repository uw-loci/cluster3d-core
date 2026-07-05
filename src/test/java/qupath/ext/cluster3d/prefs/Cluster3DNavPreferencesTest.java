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

package qupath.ext.cluster3d.prefs;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Regression guard for the Windows "Key too long" crash: a full project path used
 * verbatim as a preference key overflows Java Preferences' 80-char
 * {@code MAX_KEY_LENGTH} and throws. The axis key must hash the project path to a
 * short token so the key stays within the limit regardless of path length.
 */
class Cluster3DNavPreferencesTest {

    /** Java Preferences MAX_KEY_LENGTH. */
    private static final int MAX_KEY_LENGTH = 80;

    @Test
    void axisKeyStaysUnderPreferencesLimitForVeryLongPath() {
        String piece = "F:\\Multiplex demo images\\pyramidal crops\\multiplexTesting\\project.qpproj";
        StringBuilder sb = new StringBuilder();
        while (sb.length() < 250) {
            sb.append(piece).append("\\");
        }
        String projectKey = sb.toString();
        assertThat(projectKey.length()).isGreaterThan(200);

        for (String axis : new String[] {"x", "y", "z"}) {
            String key = Cluster3DNavPreferences.axisKey(projectKey, axis);
            assertThat(key.length())
                    .as("axis key '%s' must fit Preferences MAX_KEY_LENGTH", key)
                    .isLessThanOrEqualTo(MAX_KEY_LENGTH);
        }
    }

    @Test
    void axisKeyHandlesNullAndBlankProjectKey() {
        assertThat(Cluster3DNavPreferences.axisKey(null, "x").length()).isLessThanOrEqualTo(MAX_KEY_LENGTH);
        assertThat(Cluster3DNavPreferences.axisKey("", "z").length()).isLessThanOrEqualTo(MAX_KEY_LENGTH);
    }

    @Test
    void shortHashIsShortAndStable() {
        String s = "some/very/long/project/path/segment".repeat(20);
        String h1 = Cluster3DNavPreferences.shortHash(s);
        String h2 = Cluster3DNavPreferences.shortHash(s);
        assertThat(h1).hasSizeLessThanOrEqualTo(16);
        assertThat(h1).isEqualTo(h2);
    }
}
