package com.oj.judge.search.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.oj.judge.model.entity.Problem;

/**
 * passage 的两条硬约束：含标题、**不带 e5 前缀**（前缀只有边车加），以及按字符数兜底截断。
 */
class ProblemPassageTest {

    @Test
    void passageIsTitlePlusContent() {
        Problem problem = new Problem();
        problem.setTitle("Two Sum");
        problem.setContent("<p>Find two indices.</p>");
        problem.setLang("en");

        String passage = ProblemDocumentBuilder.passageOf(ProblemDocumentBuilder.build(problem), 6000);

        assertTrue(passage.startsWith("Two Sum "));
        assertTrue(passage.contains("Find two indices."));
    }

    @Test
    void passageNeverCarriesTheE5Prefix() {
        Problem problem = new Problem();
        problem.setTitle("两数之和");
        problem.setContent("找出两个数");
        problem.setLang("zh");

        String passage = ProblemDocumentBuilder.passageOf(ProblemDocumentBuilder.build(problem), 6000);

        assertFalse(passage.startsWith("query: "));
        assertFalse(passage.startsWith("passage: "));
        assertEquals("两数之和 找出两个数", passage);
    }

    @Test
    void passageIsTruncatedByCharBudget() {
        Problem problem = new Problem();
        problem.setTitle("T");
        problem.setContent(repeat('x', 5000));
        problem.setLang("en");

        String passage = ProblemDocumentBuilder.passageOf(ProblemDocumentBuilder.build(problem), 100);

        assertEquals(100, passage.length());
    }

    private String repeat(char value, int count) {
        StringBuilder sb = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            sb.append(value);
        }
        return sb.toString();
    }
}
