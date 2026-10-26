package com.oj.judge.search.embed;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 确定性测试替身（spec S3）：把文本哈希成固定的 384 维单位向量，
 * 不联网、不加载模型，让 search/ 的编排逻辑在没有边车的机器上也能构造输入。
 *
 * <p>它是实现接口后的 seam 验证：调用方只依赖 {@link EmbeddingClient}，
 * 换掉边车不需要改它们。
 */
public class FakeEmbeddingClient implements EmbeddingClient {

    public static final int DIMS = 384;

    private final String modelId;

    public FakeEmbeddingClient() {
        this("fake-embedding");
    }

    public FakeEmbeddingClient(String modelId) {
        this.modelId = modelId;
    }

    @Override
    public String modelId() {
        return modelId;
    }

    @Override
    public float[] embedQuery(String text) {
        return vector(text);
    }

    @Override
    public List<float[]> embedPassages(List<String> texts) {
        List<float[]> out = new ArrayList<>(texts.size());
        for (String text : texts) {
            out.add(vector(text));
        }
        return out;
    }

    private static float[] vector(String text) {
        Random random = new Random(text == null ? 0 : text.hashCode());
        double[] values = new double[DIMS];
        double norm = 0;
        for (int i = 0; i < DIMS; i++) {
            values[i] = random.nextGaussian();
            norm += values[i] * values[i];
        }
        norm = Math.sqrt(norm);
        float[] out = new float[DIMS];
        for (int i = 0; i < DIMS; i++) {
            out[i] = (float) (values[i] / norm);
        }
        return out;
    }
}
