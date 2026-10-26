"""Pure-function tests for eval_p5.py (spec S2). Standard library only.

Run: python benchmark/test_eval_p5.py
"""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from eval_p5 import group, hit_at, ndcg_at, recall_at, reciprocal_rank, row, summarize  # noqa: E402


class MetricTest(unittest.TestCase):
    def test_hit_at_boundary(self):
        self.assertEqual(hit_at([1, 2, 3], 3, 5), 1.0)
        self.assertEqual(hit_at([1, 2, 3], 3, 2), 0.0)

    def test_reciprocal_rank(self):
        self.assertEqual(reciprocal_rank([7, 8, 9], 7), 1.0)
        self.assertEqual(reciprocal_rank([7, 8, 9], 8), 0.5)
        self.assertEqual(reciprocal_rank([7, 8, 9], 99), 0.0)

    def test_ndcg_at(self):
        self.assertEqual(ndcg_at([7], 7, 10), 1.0)
        self.assertAlmostEqual(ndcg_at([1, 2, 7], 7, 10), 1.0 / 2.0)  # log2(4)
        self.assertEqual(ndcg_at([1, 2, 3], 7, 10), 0.0)

    def test_recall_at_uses_extended_set(self):
        self.assertEqual(recall_at([1, 2, 3], [2, 3, 4], 10), 2 / 3)
        self.assertEqual(recall_at([1], [], 10), 0.0)


class SummarizeTest(unittest.TestCase):
    def runs(self):
        return [
            {"lang": "en", "band": "high", "problemId": 1, "hits": [1, 2], "relevant": [1], "latencyMs": 10},
            {"lang": "zh", "band": "low", "problemId": 2, "hits": [9, 8], "relevant": [2], "latencyMs": 30},
        ]

    def test_overall(self):
        stats = summarize(self.runs())
        self.assertEqual(stats["n"], 2)
        self.assertEqual(stats["HitRate@5"], 0.5)
        self.assertEqual(stats["MRR"], 0.5)
        self.assertEqual(stats["avgLatencyMs"], 20)

    def test_group(self):
        by_lang = group(self.runs(), "lang")
        self.assertEqual(by_lang["en"]["n"], 1)
        self.assertEqual(by_lang["zh"]["HitRate@5"], 0.0)

    def test_row_shape(self):
        self.assertTrue(row("全部", summarize(self.runs())).startswith("| 全部 | 2 | 0.500 |"))
        self.assertTrue(row("全部", summarize(self.runs()), "bm25").startswith("| 全部 | bm25 | 2 |"))


if __name__ == "__main__":
    unittest.main()
