package com.oj.judge.memory.skill;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.oj.judge.memory.event.LearningAcceptedEvent;
import com.oj.judge.memory.mapper.LearningSkillMapper;
import com.oj.judge.memory.model.LearningSkillUsageDO;

/**
 * success_count 归因：只认未被领用过的 usage 行，并发/重复 AC 不会加两次。
 */
@ExtendWith(MockitoExtension.class)
class SkillAttributionServiceTest {

    @Mock
    private LearningSkillMapper skillMapper;
    @InjectMocks
    private SkillAttributionService service;

    private static LearningSkillUsageDO usage(long id, long skillId) {
        LearningSkillUsageDO row = new LearningSkillUsageDO();
        row.setId(id);
        row.setSkillId(skillId);
        row.setUid(7L);
        row.setProblemId(1L);
        row.setUsedAt(new Date());
        row.setRewarded(false);
        return row;
    }

    @Test
    void anAcceptedSubmissionRewardsOnlyUnclaimedRows() {
        when(skillMapper.selectRewardable(eq(7L), eq(1L), any(Date.class)))
                .thenReturn(List.of(usage(11L, 100L), usage(12L, 101L)));
        // Row 11 is claimed by this AC; row 12 was already claimed by a race.
        when(skillMapper.markRewarded(11L)).thenReturn(1);
        when(skillMapper.markRewarded(12L)).thenReturn(0);

        service.onAccepted(new LearningAcceptedEvent(7L, 1L));

        verify(skillMapper).incrementSuccess(100L);
        verify(skillMapper, never()).incrementSuccess(101L);
    }

    @Test
    void aSubmissionWithoutSkillUsageRewardsNothing() {
        when(skillMapper.selectRewardable(eq(7L), eq(1L), any(Date.class))).thenReturn(List.of());
        assertDoesNotThrow(() -> service.onAccepted(new LearningAcceptedEvent(7L, 1L)));
        verify(skillMapper, never()).incrementSuccess(anyLong());
    }

    @Test
    void anEventWithoutAProblemIsIgnored() {
        assertDoesNotThrow(() -> service.onAccepted(new LearningAcceptedEvent(7L, 0L)));
        assertDoesNotThrow(() -> service.onAccepted(new LearningAcceptedEvent(null, 1L)));
        verify(skillMapper, never()).selectRewardable(anyLong(), anyLong(), any(Date.class));
    }
}
