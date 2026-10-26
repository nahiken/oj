package com.oj.judge.memory.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.oj.common.core.exception.BusinessException;
import com.oj.judge.memory.mapper.LearningSkillMapper;
import com.oj.judge.memory.model.LearningSkillDO;
import com.oj.judge.memory.skill.search.SkillIndexer;

import cn.hutool.core.lang.Snowflake;

/**
 * 状态机与权限的纯对象测试：不起 Spring、不连库。
 *
 * <p>覆盖 4 条合法边与 8 条非法边（4 状态 × 非相邻转移），外加自审拒绝。
 */
@ExtendWith(MockitoExtension.class)
class SkillServiceTest {

    @Mock
    private LearningSkillMapper skillMapper;
    @Mock
    private Snowflake snowflake;
    @Mock
    private SkillIndexWriter skillIndexWriter;
    @InjectMocks
    private SkillService service;

    private LearningSkillDO skill;

    @BeforeEach
    void setUp() {
        skill = new LearningSkillDO();
        skill.setId(1L);
        skill.setScope(LearningSkillDO.SCOPE_PERSONAL);
        skill.setOwnerUid(7L);
        skill.setStatus(LearningSkillDO.STATUS_DRAFT);
        when(skillMapper.selectById(anyLong())).thenReturn(skill);
        lenient().when(snowflake.nextId()).thenReturn(99L);
    }

    @Test
    void ownerSubmitsDraftForReview() {
        LearningSkillDO result = service.submit(7L, 1L);
        assertEquals(LearningSkillDO.STATUS_REVIEW, result.getStatus());
        verify(skillMapper).updateStatus(anyLong(), anyString(), any(), any());
        verify(skillMapper).insertAudit(any());
    }

    @Test
    void anotherUserCannotSubmitSomeoneElsesSkill() {
        assertThrows(BusinessException.class, () -> service.submit(8L, 1L));
        verify(skillMapper, never()).updateStatus(anyLong(), anyString(), any(), any());
    }

    @Test
    void adminApprovesFromReview() {
        skill.setStatus(LearningSkillDO.STATUS_REVIEW);
        assertEquals(LearningSkillDO.STATUS_ACTIVE, service.review(1L, 1L, true, null).getStatus());
    }

    @Test
    void adminRejectsWithReason() {
        skill.setStatus(LearningSkillDO.STATUS_REVIEW);
        assertEquals(LearningSkillDO.STATUS_DRAFT,
                service.review(1L, 1L, false, "步骤不可复现").getStatus());
    }

    @Test
    void rejectWithoutReasonIsRefused() {
        skill.setStatus(LearningSkillDO.STATUS_REVIEW);
        assertThrows(BusinessException.class, () -> service.review(1L, 1L, false, " "));
    }

    @Test
    void authorCannotApproveTheirOwnSkill() {
        skill.setStatus(LearningSkillDO.STATUS_REVIEW);
        assertThrows(BusinessException.class, () -> service.review(7L, 1L, true, null));
        verify(skillMapper, never()).updateStatus(anyLong(), anyString(), any(), any());
    }

    @Test
    void activeCanBeDeprecated() {
        skill.setStatus(LearningSkillDO.STATUS_ACTIVE);
        assertEquals(LearningSkillDO.STATUS_DEPRECATED, service.deprecate(1L, 1L).getStatus());
    }

    @Test
    void illegalEdgesAreRejectedWithSkillState() {
        // From DRAFT: only DRAFT->REVIEW exists.
        skill.setStatus(LearningSkillDO.STATUS_DRAFT);
        assertThrows(BusinessException.class, () -> service.review(1L, 1L, true, null));
        assertThrows(BusinessException.class, () -> service.deprecate(1L, 1L));

        // From REVIEW: approve/reject only.
        skill.setStatus(LearningSkillDO.STATUS_REVIEW);
        assertThrows(BusinessException.class, () -> service.submit(7L, 1L));
        assertThrows(BusinessException.class, () -> service.deprecate(1L, 1L));

        // From ACTIVE: only deprecate.
        skill.setStatus(LearningSkillDO.STATUS_ACTIVE);
        assertThrows(BusinessException.class, () -> service.submit(7L, 1L));
        assertThrows(BusinessException.class, () -> service.review(1L, 1L, true, null));

        // From DEPRECATED: nothing.
        skill.setStatus(LearningSkillDO.STATUS_DEPRECATED);
        assertThrows(BusinessException.class, () -> service.submit(7L, 1L));
        assertThrows(BusinessException.class, () -> service.review(1L, 1L, true, null));
        assertThrows(BusinessException.class, () -> service.deprecate(1L, 1L));
    }

    @Test
    void platformSkillIsNotOwnedByAnyone() {
        skill.setScope(LearningSkillDO.SCOPE_PLATFORM);
        skill.setOwnerUid(null);
        // Even the admin who created it cannot "submit someone else's" platform skill.
        assertThrows(BusinessException.class, () -> service.submit(1L, 1L));
    }

    @Test
    void markUsedIgnoresAnInvisibleSkill() {
        skill.setStatus(LearningSkillDO.STATUS_ACTIVE);
        skill.setOwnerUid(8L);
        int updated = service.markUsed(7L, 100L, java.util.List.of(1L));
        assertEquals(0, updated);
        verify(skillMapper, never()).incrementUsage(anyLong());
    }

    @Test
    void markUsedRecordsTheInjection() {
        skill.setStatus(LearningSkillDO.STATUS_ACTIVE);
        int updated = service.markUsed(7L, 100L, java.util.List.of(1L));
        assertEquals(1, updated);
        verify(skillMapper).incrementUsage(1L);
        verify(skillMapper).insertUsage(anyLong(), anyLong(), anyLong(), anyLong(), any());
    }

    /**
     * 双写（spec D20）：批准即写索引——「审核通过可立刻搜到」是验收项，
     * 不是"下一次重建时才会出现"。
     */
    @Test
    void approvalWritesTheIndex() {
        skill.setStatus(LearningSkillDO.STATUS_REVIEW);
        service.review(1L, 1L, true, null);
        verify(skillIndexWriter).indexAfterCommit(org.mockito.ArgumentMatchers.argThat(
                indexed -> indexed != null && LearningSkillDO.STATUS_ACTIVE.equals(indexed.getStatus())));
    }

    @Test
    void rejectionRemovesTheDocument() {
        skill.setStatus(LearningSkillDO.STATUS_REVIEW);
        service.review(1L, 1L, false, "步骤不可复现");
        verify(skillIndexWriter).deleteAfterCommit(1L);
    }

    @Test
    void deprecationRemovesTheDocument() {
        skill.setStatus(LearningSkillDO.STATUS_ACTIVE);
        service.deprecate(1L, 1L);
        verify(skillIndexWriter).deleteAfterCommit(1L);
    }

    @Test
    void illegalTransitionNeverTouchesTheIndex() {
        skill.setStatus(LearningSkillDO.STATUS_DEPRECATED);
        assertThrows(BusinessException.class, () -> service.deprecate(1L, 1L));
        verify(skillIndexWriter, never()).indexAfterCommit(any());
        verify(skillIndexWriter, never()).deleteAfterCommit(anyLong());
    }
}
