package com.oj.judge.memory.skill.search;

import java.util.ArrayList;
import java.util.List;

import com.oj.judge.memory.model.LearningSkillDO;
import com.oj.judge.memory.support.ProblemTags;

/**
 * MySQL 行 → 索引文档（纯函数，不碰 ES、不碰边车）。
 *
 * <p>三件事必须在这里定死，否则单条写入与全量重建会写出两种形状的文档：
 * 标签从 JSON 字符串解析成数组、`uid` 只对 PERSONAL 写入、正文按契约截到上限。
 */
public final class SkillDocumentBuilder {

    private SkillDocumentBuilder() {
    }

    public static SkillDocument build(LearningSkillDO skill, int contentMaxChars) {
        SkillDocument document = new SkillDocument();
        document.setSkillId(skill.getId());
        document.setName(skill.getName());
        document.setContent(truncate(skill.getContent(), contentMaxChars));
        document.setTags(ProblemTags.parse(skill.getTags()));
        document.setScope(skill.getScope());
        // 平台 Skill 没有归属人：写空而不是写 0，避免「uid=0」被当成一个真实用户。
        document.setUid(LearningSkillDO.SCOPE_PERSONAL.equals(skill.getScope()) && skill.getOwnerUid() != null
                ? String.valueOf(skill.getOwnerUid())
                : null);
        return document;
    }

    /**
     * 送进 embedding 边车的文本。
     *
     * <p>名称权重靠 BM25 的 `name^3`，这里不重复加权；把标签也放进来是因为
     * 「二分」「并查集」这类词经常只出现在 tags 里，正文里一次都不出现。
     */
    public static String passageOf(SkillDocument document) {
        StringBuilder passage = new StringBuilder();
        if (document.getName() != null) {
            passage.append(document.getName());
        }
        if (document.getContent() != null && !document.getContent().isBlank()) {
            passage.append('\n').append(document.getContent());
        }
        List<String> tags = document.getTags();
        if (tags != null && !tags.isEmpty()) {
            passage.append('\n').append(String.join(", ", tags));
        }
        return passage.toString();
    }

    /** 只截一次：写入与重建共用，保证同一条 Skill 的文档长度一致。 */
    static String truncate(String text, int maxChars) {
        if (text == null) {
            return null;
        }
        if (maxChars <= 0 || text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars);
    }
}
