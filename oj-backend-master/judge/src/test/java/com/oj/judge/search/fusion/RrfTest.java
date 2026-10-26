package com.oj.judge.search.fusion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * RRF 的三条性质：名次越前分越高、单路未命中不参与求和、同分时用名次稳定裁决。
 */
class RrfTest {

    @Test
    void firstPlaceScoresTwoOverSixtyOneWithTwoRoutes() {
        Map<Long, Double> scores = Rrf.fuse(
                List.of(List.of(1L), List.of(1L)), List.of(1.0, 1.0), 60);

        assertEquals(2.0 / 61, scores.get(1L), 1e-9);
    }

    @Test
    void missingFromOneRouteOnlyCountsTheOther() {
        Map<Long, Double> scores = Rrf.fuse(
                List.of(List.of(1L, 2L), List.of(2L)), List.of(1.0, 1.0), 60);

        assertEquals(1.0 / 61, scores.get(1L), 1e-9);
        assertEquals(1.0 / 62 + 1.0 / 61, scores.get(2L), 1e-9);
        assertTrue(scores.get(2L) > scores.get(1L), "两路都命中应高于只命中一路");
    }

    @Test
    void orderIsStableOnTies() {
        // 两路第一名互换：融合分完全相同，靠第一路名次裁决
        Map<Long, Double> scores = Rrf.fuse(
                List.of(List.of(1L, 2L), List.of(2L, 1L)), List.of(1.0, 1.0), 60);

        List<Long> ordered = Rrf.order(scores, List.of(List.of(1L, 2L), List.of(2L, 1L)));

        assertEquals(List.of(1L, 2L), ordered);
    }

    @Test
    void deeperRanksScoreLess() {
        Map<Long, Double> scores = Rrf.fuse(
                List.of(List.of(5L, 6L, 7L), List.of()), List.of(1.0, 1.0), 60);

        assertTrue(scores.get(5L) > scores.get(6L));
        assertTrue(scores.get(6L) > scores.get(7L));
        assertFalse(scores.containsKey(99L));
    }
}
