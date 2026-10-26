package com.oj.judge.search.embed;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * S3 的 seam 验证：调用方只依赖 {@link EmbeddingClient}，测试替身确定且可离线运行。
 */
class FakeEmbeddingClientTest {

    @Test
    void sameTextProducesSameUnitVector() {
        EmbeddingClient client = new FakeEmbeddingClient();

        float[] first = client.embedQuery("two sum");
        float[] second = client.embedQuery("two sum");

        assertEquals(FakeEmbeddingClient.DIMS, first.length);
        assertArrayEquals(first, second, 0f);
        double norm = 0;
        for (float value : first) {
            norm += value * value;
        }
        assertEquals(1.0, Math.sqrt(norm), 1e-6);
    }

    @Test
    void differentTextProducesDifferentVector() {
        EmbeddingClient client = new FakeEmbeddingClient();
        assertFalse(java.util.Arrays.equals(client.embedQuery("a"), client.embedQuery("b")));
    }

    @Test
    void passagesKeepOrderAndModelId() {
        EmbeddingClient client = new FakeEmbeddingClient("fake-v2");

        List<float[]> vectors = client.embedPassages(List.of("one", "two"));

        assertEquals(2, vectors.size());
        assertArrayEquals(client.embedQuery("one"), vectors.get(0), 0f);
        assertArrayEquals(client.embedQuery("two"), vectors.get(1), 0f);
        assertEquals("fake-v2", client.modelId());
    }
}
