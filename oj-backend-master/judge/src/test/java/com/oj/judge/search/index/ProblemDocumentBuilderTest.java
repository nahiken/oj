package com.oj.judge.search.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Date;

import org.junit.jupiter.api.Test;

import com.oj.judge.model.entity.Problem;
import com.oj.judge.search.support.HtmlText;

/**
 * 索引文档的两条结构规则：语种决定正文进哪个子字段、外部版本号必须为正。
 * 这两条如果错了，BM25 静默搜不到中文，或者 ES 直接拒绝写入。
 */
class ProblemDocumentBuilderTest {

    @Test
    void chineseProblemGoesToZhSubfield() {
        Problem problem = problem("zh", "<p>给定一个整数数组，返回两数之和</p>");

        ProblemDocument doc = ProblemDocumentBuilder.build(problem);

        assertNull(doc.getContent().getEn());
        assertEquals("给定一个整数数组，返回两数之和", doc.getContent().getZh());
        assertEquals("zh", doc.getLang());
    }

    @Test
    void missingLangDefaultsToEnglish() {
        Problem problem = problem(null, "Given an array, return two indices");

        ProblemDocument doc = ProblemDocumentBuilder.build(problem);

        assertEquals("en", doc.getLang());
        assertNull(doc.getContent().getZh());
        assertTrue(doc.getContent().getEn().startsWith("Given an array"));
    }

    @Test
    void versionFallsBackToCreateTimeThenOne() {
        Problem withUpdate = problem("en", "x");
        withUpdate.setUpdateTime(new Date(1_700_000_000_000L));
        assertEquals(1_700_000_000_000L, ProblemDocumentBuilder.versionOf(withUpdate));

        Problem withCreate = problem("en", "x");
        withCreate.setCreateTime(new Date(1_600_000_000_000L));
        assertEquals(1_600_000_000_000L, ProblemDocumentBuilder.versionOf(withCreate));

        assertEquals(1L, ProblemDocumentBuilder.versionOf(problem("en", "x")), "external 版本必须为正数");
    }

    @Test
    void deletedFlagAndTagsAreCarried() {
        Problem problem = problem("en", "text");
        problem.setIsDelete(1);
        problem.setTags("[\"array\",\"hash\"]");

        ProblemDocument doc = ProblemDocumentBuilder.build(problem);

        assertTrue(doc.getIsDelete());
        assertEquals(2, doc.getTags().size());
        assertEquals("array", doc.getTags().get(0));
    }

    @Test
    void htmlIsStrippedAndEntitiesDecoded() {
        // 先剥标签再解实体：否则 `&lt;` 解码出的 `<` 会被当成标签开头，把后面的正文一起吞掉
        assertEquals("a < b", HtmlText.strip("<p>a &lt; b</p>"));
        assertEquals("x y", HtmlText.strip("x\n\n\t y"));
        assertEquals("", HtmlText.strip(null));
        assertFalse(HtmlText.strip("<p>tag</p>").contains("<"));
    }

    private Problem problem(String lang, String content) {
        Problem problem = new Problem();
        problem.setId(910001L);
        problem.setTitle("Two Sum");
        problem.setContent(content);
        problem.setDifficulty("EASY");
        problem.setLang(lang);
        problem.setSource("imported");
        problem.setIsDelete(0);
        return problem;
    }
}
