package com.oj.judge.memory.skill;

import javax.annotation.Resource;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.oj.judge.memory.model.LearningSkillDO;
import com.oj.judge.memory.skill.search.SkillIndexer;

import lombok.extern.slf4j.Slf4j;

/**
 * Skill 索引双写（spec D20），保证「ES 写入发生在 MySQL 提交之后」。
 *
 * <p>为什么不是直接在 Service 里调 {@link SkillIndexer}：ES 没有事务，写早了（提交前）
 * 一旦数据库回滚，就会留下一条「MySQL 里不存在、但 search_skill 能搜到」的文档。
 * 晋升路径已经用「事务返回后再写」守住了这条，审核/下架路径曾经漏了，所以统一收在这里。
 *
 * <p>索引失败**不回滚**状态机：MySQL 是真相，ES 可以由全量重建修好（{@code /search/admin/reindex}）。
 * 所以 afterCommit 里的异常吞掉并计数，不往上抛。
 */
@Slf4j
@Component
public class SkillIndexWriter {

    @Resource
    private SkillIndexer indexer;

    /** 审核通过：提交后让这条 Skill 立刻可搜。 */
    public void indexAfterCommit(LearningSkillDO skill) {
        afterCommit("index", () -> indexer.index(skill));
    }

    /** 打回 / 下架：提交后从索引删除，避免「已下架却仍被 agent 引用」。 */
    public void deleteAfterCommit(Long skillId) {
        afterCommit("delete", () -> indexer.delete(skillId));
    }

    private void afterCommit(String action, Runnable write) {
        Runnable guarded = () -> {
            try {
                write.run();
            } catch (RuntimeException e) {
                log.warn("evt=memory.skill.index_failed action={} hint=可由 /search/admin/reindex 全量重建", action, e);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    guarded.run();
                }
            });
            return;
        }
        // 没有事务（脚本、单测直接调 Service）时没有「提交后」可等，立刻写
        guarded.run();
    }
}
