package com.oj.judge.search.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * 字段选择决定"中文能不能被搜到"：中文查询必须落在 smartcn 分析的子字段上。
 */
class SearchQueryFieldsTest {

    @Test
    void chineseUsesSmartcnFieldOnly() {
        List<String> fields = SearchQueryService.queryFields("zh");

        assertTrue(fields.contains("title.smartcn^2"));
        assertTrue(fields.contains("content.zh"));
        assertFalse(fields.contains("content.en"), "中文查询不应落到 standard 分析的英文字段");
    }

    @Test
    void englishUsesStandardField() {
        List<String> fields = SearchQueryService.queryFields("en");

        assertTrue(fields.contains("title^2"));
        assertTrue(fields.contains("content.en"));
        assertFalse(fields.contains("content.zh"));
    }

    @Test
    void noLangSearchesBoth() {
        List<String> fields = SearchQueryService.queryFields(null);

        assertTrue(fields.containsAll(List.of("title^2", "title.smartcn^2", "content.en", "content.zh", "tags")));
        assertEquals(5, fields.size());
    }
}
