package com.openhire.aiworkers.matching.domain;

import java.util.Comparator;
import java.util.List;

/** Stage 3: exact cosine top-k over one candidate's chunks. */
public final class Retrieval {
    private Retrieval() {}

    public record Hit(StoredChunk chunk, double similarity) {}

    public static List<Hit> topK(double[] query, List<StoredChunk> chunks, int k) {
        return chunks.stream()
                .map(chunk -> new Hit(chunk, cosine(query, chunk.vector())))
                .sorted(Comparator.comparingDouble(Hit::similarity)
                        .reversed()
                        .thenComparingInt(hit -> hit.chunk().chunk().index()))
                .limit(k)
                .toList();
    }

    public static double cosine(double[] a, double[] b) {
        if (a.length == 0 || a.length != b.length) return 0;
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return na == 0 || nb == 0 ? 0 : dot / (Math.sqrt(na) * Math.sqrt(nb));
    }
}
