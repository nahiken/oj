package com.oj.judge.search.fusion;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reciprocal Rank Fusion（契约 0005 §4.4）。
 *
 * <p>`score(d) = Σ_c w_c / (k + rank_c(d))`，`k=60`、`w=1.0` 是**契约值**，不在评估集上调参。
 * 单路未命中的文档不参与该项求和（不是按 0 分参与），否则"只在 BM25 里出现"的文档会被稀释。
 *
 * <p>纯函数，无 ES、无边车：融合逻辑的正确性靠单测保证，不靠端到端对数字。
 */
public final class Rrf {

    private Rrf() {
    }

    /**
     * @param rankings 每路的 id 列表，**按名次从 1 开始**（index 0 是第 1 名）
     * @param weights  与 rankings 一一对应的权重
     * @param k        平滑常数，契约固定 60
     */
    public static Map<Long, Double> fuse(List<List<Long>> rankings, List<Double> weights, int k) {
        Map<Long, Double> scores = new HashMap<>();
        for (int route = 0; route < rankings.size(); route++) {
            List<Long> ranked = rankings.get(route);
            double weight = weights.get(route);
            int rank = 0;
            for (Long id : ranked) {
                rank++;
                if (id == null) {
                    continue;
                }
                scores.merge(id, weight / (k + rank), Double::sum);
            }
        }
        return scores;
    }

    /**
     * 按融合分降序排；同分时依次用每一路的名次做稳定裁决（名次靠前者在前），
     * 避免同一个 query 两次调用返回不同顺序。
     *
     * @param rankings 已按名次排好的多路 id 列表
     */
    public static List<Long> order(Map<Long, Double> scores, List<List<Long>> rankings) {
        List<Map<Long, Integer>> positions = new ArrayList<>(rankings.size());
        for (List<Long> ranked : rankings) {
            Map<Long, Integer> position = new HashMap<>();
            int rank = 0;
            for (Long id : ranked) {
                rank++;
                position.putIfAbsent(id, rank);
            }
            positions.add(position);
        }
        List<Long> ordered = new ArrayList<>(scores.keySet());
        ordered.sort((left, right) -> {
            int byScore = Double.compare(scores.get(right), scores.get(left));
            if (byScore != 0) {
                return byScore;
            }
            for (Map<Long, Integer> position : positions) {
                int leftPos = position.getOrDefault(left, Integer.MAX_VALUE);
                int rightPos = position.getOrDefault(right, Integer.MAX_VALUE);
                if (leftPos != rightPos) {
                    return Integer.compare(leftPos, rightPos);
                }
            }
            return Long.compare(left, right);
        });
        return ordered;
    }

}
