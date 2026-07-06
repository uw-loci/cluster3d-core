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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class AxisAutoDetectTest {

    @Test
    void detectsUmapUnderscore() {
        List<String> r = AxisAutoDetect.detect(List.of("UMAP_1", "UMAP_2", "UMAP_3", "Area"));
        assertThat(r).containsExactly("UMAP_1", "UMAP_2", "UMAP_3");
    }

    @Test
    void detectsMixedSeparatorsAndCase() {
        List<String> r = AxisAutoDetect.detect(List.of("umap-1", "UMAP 2", "Umap3"));
        assertThat(r).containsExactly("umap-1", "UMAP 2", "Umap3");
    }

    @Test
    void detectsPcaAndReturnsFamily() {
        List<String> r = AxisAutoDetect.detect(List.of("PCA1", "PCA2", "PCA3"));
        assertThat(r).containsExactly("PCA1", "PCA2", "PCA3");
        assertThat(AxisAutoDetect.detectedFamily(List.of("PCA1", "PCA2", "PCA3")))
                .isEqualTo("PCA");
    }

    @Test
    void detectsPcWithoutMatchingPca() {
        List<String> r = AxisAutoDetect.detect(List.of("PC1", "PC2", "PC3"));
        assertThat(r).containsExactly("PC1", "PC2", "PC3");
    }

    @Test
    void detectsTsne() {
        List<String> r = AxisAutoDetect.detect(List.of("t-SNE 1", "tSNE_2", "TSNE3"));
        assertThat(r).containsExactly("t-SNE 1", "tSNE_2", "TSNE3");
    }

    @Test
    void prefersUmapWhenMultipleFamiliesPresent() {
        List<String> r = AxisAutoDetect.detect(List.of("PCA1", "PCA2", "PCA3", "UMAP1", "UMAP2", "UMAP3"));
        assertThat(r).containsExactly("UMAP1", "UMAP2", "UMAP3");
    }

    @Test
    void incompleteTripleReturnsEmpty() {
        assertThat(AxisAutoDetect.detect(List.of("UMAP1", "UMAP2"))).isEmpty();
    }

    @Test
    void nonEmbeddingReturnsEmpty() {
        assertThat(AxisAutoDetect.detect(List.of("Area", "Perimeter", "Circularity")))
                .isEmpty();
    }

    @Test
    void emptyInputReturnsEmpty() {
        assertThat(AxisAutoDetect.detect(List.of())).isEmpty();
        assertThat(AxisAutoDetect.detect(null)).isEmpty();
    }

    // --- 2D pair detection ---

    @Test
    void detectsPairFromTwoComponentEmbedding() {
        List<String> r = AxisAutoDetect.detectPair(List.of("UMAP1", "UMAP2", "Area"));
        assertThat(r).containsExactly("UMAP1", "UMAP2");
        assertThat(AxisAutoDetect.detectedFamilyPair(List.of("UMAP1", "UMAP2")))
                .isEqualTo("UMAP");
    }

    @Test
    void detectsPairFromThreeComponentEmbedding() {
        // Only the first two components are used for the 2D pair.
        assertThat(AxisAutoDetect.detectPair(List.of("UMAP1", "UMAP2", "UMAP3")))
                .containsExactly("UMAP1", "UMAP2");
    }

    @Test
    void pairRequiresTwoComponents() {
        assertThat(AxisAutoDetect.detectPair(List.of("UMAP1"))).isEmpty();
        assertThat(AxisAutoDetect.detectPair(List.of("Area", "Perimeter"))).isEmpty();
    }

    // --- two-of-three scientific-correctness guard ---

    @Test
    void twoOfThreeFlagsAxesFromA3dEmbedding() {
        List<String> names = List.of("UMAP1", "UMAP2", "UMAP3", "Area");
        assertThat(AxisAutoDetect.isTwoOfThree(names, "UMAP1", "UMAP2")).isTrue();
    }

    @Test
    void twoOfThreeDoesNotFlagAGenuine2dEmbedding() {
        List<String> names = List.of("UMAP1", "UMAP2", "Area", "Perimeter");
        assertThat(AxisAutoDetect.isTwoOfThree(names, "UMAP1", "UMAP2")).isFalse();
    }

    @Test
    void twoOfThreeDoesNotFlagNonEmbeddingAxes() {
        List<String> names = List.of("UMAP1", "UMAP2", "UMAP3", "Area", "Perimeter");
        assertThat(AxisAutoDetect.isTwoOfThree(names, "Area", "Perimeter")).isFalse();
    }

    @Test
    void twoOfThreeRequiresSameFamily() {
        // A UMAP axis and a PCA axis are not two components of one embedding.
        List<String> names = List.of("UMAP1", "UMAP2", "UMAP3", "PCA1", "PCA2", "PCA3");
        assertThat(AxisAutoDetect.isTwoOfThree(names, "UMAP1", "PCA2")).isFalse();
    }
}
