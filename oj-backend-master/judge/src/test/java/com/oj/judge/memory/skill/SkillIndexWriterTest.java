package com.oj.judge.memory.skill;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.oj.judge.memory.model.LearningSkillDO;
import com.oj.judge.memory.skill.search.SkillIndexer;

/**
 * 索引写入必须发生在**事务提交之后**（spec D20）：提交前写 ES，一旦数据库回滚，
 * 就会留下一条「MySQL 里没有、search_skill 却能搜到」的幽灵文档。
 *
 * <p>这里用 Spring 的事务同步器直接构造「事务进行中」的状态，不连库、不起上下文。
 */
class SkillIndexWriterTest {

    private final SkillIndexer indexer = mock(SkillIndexer.class);
    private final SkillIndexWriter writer = newWriter();

    private SkillIndexWriter newWriter() {
        SkillIndexWriter target = new SkillIndexWriter();
        ReflectionTestUtils.setField(target, "indexer", indexer);
        return target;
    }

    @AfterEach
    void clearTransaction() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void indexWaitsForCommitWhenTransactionIsActive() {
        TransactionSynchronizationManager.initSynchronization();
        LearningSkillDO skill = skill(7L);

        writer.indexAfterCommit(skill);
        verify(indexer, never()).index(any());

        List<TransactionSynchronization> registered = TransactionSynchronizationManager.getSynchronizations();
        registered.forEach(TransactionSynchronization::afterCommit);
        verify(indexer).index(skill);
    }

    @Test
    void deleteWaitsForCommitWhenTransactionIsActive() {
        TransactionSynchronizationManager.initSynchronization();

        writer.deleteAfterCommit(7L);
        verify(indexer, never()).delete(any());

        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        verify(indexer).delete(7L);
    }

    @Test
    void rollbackNeverWritesTheIndex() {
        TransactionSynchronizationManager.initSynchronization();

        writer.indexAfterCommit(skill(7L));
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        verify(indexer, never()).index(any());
    }

    @Test
    void writesImmediatelyWithoutTransaction() {
        LearningSkillDO skill = skill(7L);
        writer.indexAfterCommit(skill);
        verify(indexer).index(skill);
        writer.deleteAfterCommit(7L);
        verify(indexer).delete(7L);
    }

    @Test
    void indexFailureDoesNotBreakTheCaller() {
        // ES 挂了不能把已经提交的状态机再炸一次；重建由 /search/admin/reindex 兜底。
        doThrow(new IllegalStateException("es down")).when(indexer).index(any());
        assertDoesNotThrow(() -> writer.indexAfterCommit(skill(7L)));
    }

    private LearningSkillDO skill(Long id) {
        LearningSkillDO skill = new LearningSkillDO();
        skill.setId(id);
        skill.setStatus(LearningSkillDO.STATUS_ACTIVE);
        return skill;
    }
}
