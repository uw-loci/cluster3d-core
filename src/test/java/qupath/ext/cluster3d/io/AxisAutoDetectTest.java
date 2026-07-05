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
}
