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

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Window;

/**
 * Best-effort QuPath light/dark theme detection (by stylesheet name) plus
 * AA-contrast-compliant accent colors picked per theme. Centralized so the
 * Canvas, the main window, and the axis picker all read the same signal and use
 * consistent colors that stay legible on either theme.
 */
final class ThemeUtils {

    private ThemeUtils() {}

    /** True if a "dark"-named stylesheet is applied at the scene or application level. */
    static boolean isDark(Scene scene) {
        if (scene != null) {
            for (String s : scene.getStylesheets()) {
                if (s != null && s.toLowerCase().contains("dark")) {
                    return true;
                }
            }
            String ua = scene.getUserAgentStylesheet();
            if (ua != null && ua.toLowerCase().contains("dark")) {
                return true;
            }
        }
        String appUa = Application.getUserAgentStylesheet();
        return appUa != null && appUa.toLowerCase().contains("dark");
    }

    static boolean isDark(Window window) {
        return window != null && isDark(window.getScene());
    }

    /**
     * Green "auto-detected" accent. Dark theme: light green (>= 4.5:1 on a dark
     * panel). Light theme: dark green (>= 4.5:1 on white). Both meet WCAG AA for
     * normal text.
     */
    static String autoDetectStyle(boolean dark) {
        return dark ? "-fx-text-fill: #7ec97e;" : "-fx-text-fill: #157a32;";
    }

    /** Amber "warning" accent, AA-compliant per theme. */
    static String warningStyle(boolean dark) {
        return dark ? "-fx-text-fill: #e6b800;" : "-fx-text-fill: #8a5a00;";
    }
}
