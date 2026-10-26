package com.oj.judge.memory.skill.search;

import com.oj.judge.memory.model.LearningSkillDO;

/**
 * 双写缝：Skill 状态变化 → 索引变化。
 *
 * <p>为什么是接口：状态机（{@code SkillService}）不该知道 ES 存在。单测用 Mockito 断言
 * 「批准就写、下架就删」，不需要起 ES；实现类负责把失败吞掉并计数，因为
 * **索引写失败不能让状态转移回滚**——MySQL 才是真相，索引可以重建。
 */
public interface SkillIndexer {

    /** 写入或覆盖一条 ACTIVE Skill 的文档。 */
    void index(LearningSkillDO skill);

    /** 从索引移除（下架 / 不再可见）。 */
    void delete(Long skillId);
}
