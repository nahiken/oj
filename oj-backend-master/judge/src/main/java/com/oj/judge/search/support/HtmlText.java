package com.oj.judge.search.support;

import java.util.regex.Pattern;

/**
 * 把题库正文转成可检索纯文本。
 *
 * <p>真实题目的 `content` 是富文本/HTML，导语料是纯文本。索引与 highlight 都只认纯文本，
 * 否则片段里会出现 `&lt;p&gt;` 这类噪声。这是"索引侧的唯一真相"——查询侧不再做任何清洗。
 */
public final class HtmlText {

    private static final Pattern TAGS = Pattern.compile("<[^>]{1,200}>");
    /** 索引与片段都不保留换行：把任意空白序列压成一个空格 */
    private static final Pattern SPACES = Pattern.compile("\\s+");

    private HtmlText() {
    }

    public static String strip(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        // 先剥标签、再解实体：反过来会把 `&lt;` 解码出来的 `<` 当成标签开头，吞掉后面的正文。
        String text = TAGS.matcher(raw).replaceAll(" ");
        text = text
                .replace("&nbsp;", " ")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&amp;", "&");
        text = SPACES.matcher(text).replaceAll(" ");
        return text.trim();
    }
}
