package com.oj.judge.search.index;

import java.util.Date;

import com.oj.judge.memory.support.ProblemTags;
import com.oj.judge.model.entity.Problem;
import com.oj.judge.search.support.HtmlText;

/**
 * `problem` 行 → 索引文档。纯函数，可单测（S2 seam）。
 *
 * <p>两条规则值得记住：
 * <ul>
 *   <li>语种决定正文进哪个子字段：`zh` → `content.zh`（smartcn），其余 → `content.en`（standard）；</li>
 *   <li>外部版本号取 `update_time`，退化到 `create_time`，再退化到 1——ES 的 external 版本必须为正数。</li>
 * </ul>
 */
public final class ProblemDocumentBuilder {

    private ProblemDocumentBuilder() {
    }

    public static String langOf(Problem problem) {
        String lang = problem.getLang();
        return (lang == null || lang.isBlank()) ? "en" : lang.trim().toLowerCase();
    }

    public static long versionOf(Problem problem) {
        Date updated = problem.getUpdateTime() != null ? problem.getUpdateTime() : problem.getCreateTime();
        long millis = updated == null ? 0L : updated.getTime();
        return millis > 0 ? millis : 1L;
    }

    public static ProblemDocument build(Problem problem) {
        ProblemDocument doc = new ProblemDocument();
        doc.setProblemId(problem.getId());
        doc.setTitle(problem.getTitle() == null ? "" : problem.getTitle());
        doc.setContent(contentOf(problem));
        doc.setTags(ProblemTags.parse(problem.getTags()));
        doc.setDifficulty(problem.getDifficulty());
        doc.setLang(langOf(problem));
        doc.setSource(problem.getSource() == null || problem.getSource().isBlank() ? "native" : problem.getSource());
        doc.setIsDelete(problem.getIsDelete() != null && problem.getIsDelete() != 0);
        doc.setUpdateTime(versionOf(problem));
        return doc;
    }

    static ProblemDocument.Content contentOf(Problem problem) {
        String text = HtmlText.strip(problem.getContent());
        ProblemDocument.Content content = new ProblemDocument.Content();
        if ("zh".equals(langOf(problem))) {
            content.setZh(text);
        } else {
            content.setEn(text);
        }
        return content;
    }

    /**
     * dense 侧的 passage：`title + 正文`（契约 0005 §4.3）。
     *
     * <p>真正的 512 token 截断在边车里做（它才有 tokenizer）；这里只按字符数兜底，
     * 避免把几 MB 的脏数据发过去。**不要**在这里加 `query:` / `passage:` 前缀——前缀只有边车加。
     */
    public static String passageOf(ProblemDocument document, int maxChars) {
        ProblemDocument.Content content = document.getContent();
        String body = content == null ? "" : (content.getZh() != null ? content.getZh() : content.getEn());
        String text = document.getTitle() + " " + (body == null ? "" : body);
        return text.length() > maxChars ? text.substring(0, maxChars) : text;
    }
}
