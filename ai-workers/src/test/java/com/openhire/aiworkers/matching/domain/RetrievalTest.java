package com.openhire.aiworkers.matching.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class RetrievalTest {
    private static StoredChunk chunk(int index, double... vector) {
        return new StoredChunk(new Chunk("chk_" + index, index, "EXPERIENCE", "text " + index), vector);
    }

    @Test
    void ranksByExactCosineSimilarity() {
        var chunks = List.of(chunk(0, 0, 1, 0), chunk(1, 1, 0.1, 0), chunk(2, 1, 1, 0), chunk(3, -1, 0, 0));

        var hits = Retrieval.topK(new double[] {1, 0, 0}, chunks, 3);

        assertThat(hits).extracting(h -> h.chunk().chunk().id()).containsExactly("chk_1", "chk_2", "chk_0");
        assertThat(hits.get(0).similarity()).isGreaterThan(0.99);
    }

    @Test
    void limitsToKAndBreaksTiesByPosition() {
        var chunks = List.of(chunk(0, 1, 0), chunk(1, 1, 0), chunk(2, 1, 0));
        var hits = Retrieval.topK(new double[] {1, 0}, chunks, 2);
        assertThat(hits).extracting(h -> h.chunk().chunk().id()).containsExactly("chk_0", "chk_1");
    }

    @Test
    void mismatchedDimensionsOrZeroVectorsScoreZero() {
        assertThat(Retrieval.cosine(new double[] {1, 2}, new double[] {1, 2, 3}))
                .isZero();
        assertThat(Retrieval.cosine(new double[] {0, 0}, new double[] {1, 2})).isZero();
        assertThat(Retrieval.cosine(new double[] {2, 0}, new double[] {5, 0})).isEqualTo(1.0);
    }
}
