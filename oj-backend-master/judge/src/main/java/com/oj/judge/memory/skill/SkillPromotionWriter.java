package com.oj.judge.memory.skill;

import java.util.Date;

import javax.annotation.Resource;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.oj.judge.memory.mapper.LearningSkillMapper;
import com.oj.judge.memory.model.LearningSkillAuditDO;
import com.oj.judge.memory.model.LearningSkillDO;

import cn.hutool.core.lang.Snowflake;

/**
 * 晋升的**写事务边界**：插入公共池行 + 审计行，要么都成，要么都不成。
 *
 * <p>为什么单开一个 Bean：`@Transactional` 是代理，类内 `this.promote(...)` 调用不经过代理，
 * 每日扫描就是条“没有事务”的路径（插入成功、审计失败会留下一条查不到解释的公共行）。
 * 把写入放进独立 Bean，调用方无论是手动晋升还是扫描，都真的在事务里。
 *
 * <p>索引写入**不在这里**：ES 没有事务，写早了（提交前）一旦数据库回滚就会留下一条
 * “MySQL 里不存在但能被搜到”的文档。调用方在事务返回之后再写索引。
 */
@Component
public class SkillPromotionWriter {

    @Resource
    private LearningSkillMapper skillMapper;
    @Resource
    private Snowflake snowflake;

    /** @return 落库后的公共池行（id 已填） */
    @Transactional
    public LearningSkillDO insertPromoted(LearningSkillDO promoted, PromotionContext context) {
        skillMapper.insertPromoted(promoted);
        LearningSkillAuditDO audit = new LearningSkillAuditDO();
        audit.setId(snowflake.nextId());
        audit.setSkillId(promoted.getId());
        audit.setFromStatus(null);
        audit.setToStatus(LearningSkillDO.STATUS_ACTIVE);
        audit.setActorUid(context.actorUid());
        audit.setReason("promoted from " + context.originId());
        audit.setCreatedAt(new Date());
        skillMapper.insertAudit(audit);
        return promoted;
    }
}
