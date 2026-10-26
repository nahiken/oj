package com.oj.judge.memory.skill.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.oj.judge.memory.model.LearningSkillDO;

/**
 * 索引文档的形状（纯函数）：
 *
 * <p>这三条断言对应三个真实故障——标签没解析（JSON 字符串被当成一个标签）、
 * 平台 Skill 写了 uid（"谁的平台经验"这种问题永远答错）、正文没截断（500 字契约失效）。
 */
class SkillDocumentBuilderTest {

    private static LearningSkillDO skill(String scope, Long ownerUid) {
        LearningSkillDO skill = new LearningSkillDO();
        skill.setId(42L);
        skill.setName("二分答案三步走");
        skill.setScope(scope);
        skill.setOwnerUid(ownerUid);
        skill.setContent("先确定单调性，再二分边界，最后验证答案。");
        skill.setTags("[\"二分\",\"单调性\"]");
        return skill;
    }

    @Test
    void tagsAreParsedIntoAnArray() {
        SkillDocument document = SkillDocumentBuilder.build(skill(LearningSkillDO.SCOPE_PERSONAL, 7L), 500);
        assertEquals(List.of("二分", "单调性"), document.getTags());
    }

    @Test
    void personalSkillCarriesItsOwnerAsUid() {
        SkillDocument document = SkillDocumentBuilder.build(skill(LearningSkillDO.SCOPE_PERSONAL, 7L), 500);
        assertEquals("7", document.getUid());
    }

    @Test
    void platformSkillHasNoUid() {
        SkillDocument document = SkillDocumentBuilder.build(skill(LearningSkillDO.SCOPE_PLATFORM, null), 500);
        assertNull(document.getUid());
        assertEquals(LearningSkillDO.SCOPE_PLATFORM, document.getScope());
    }

    @Test
    void contentIsCappedAtTheContractLimit() {
        LearningSkillDO long1 = skill(LearningSkillDO.SCOPE_PERSONAL, 7L);
        long1.setContent("x".repeat(800));
        SkillDocument document = SkillDocumentBuilder.build(long1, 500);
        assertEquals(500, document.getContent().length());
    }

    @Test
    void passageCarriesNameContentAndTags() {
        SkillDocument document = SkillDocumentBuilder.build(skill(LearningSkillDO.SCOPE_PERSONAL, 7L), 500);
        String passage = SkillDocumentBuilder.passageOf(document);
        assertTrue(passage.contains("二分答案三步走"));
        assertTrue(passage.contains("先确定单调性"));
        assertTrue(passage.contains("二分, 单调性"));
    }

    @Test
    void emptyTagsAreNotAPassage() {
        LearningSkillDO noTags = skill(LearningSkillDO.SCOPE_PLATFORM, null);
        noTags.setTags(null);
        SkillDocument document = SkillDocumentBuilder.build(noTags, 500);
        assertTrue(document.getTags().isEmpty());
        assertEquals(2, SkillDocumentBuilder.passageOf(document).split("\n").length);
    }
}
