package com.oj.judge.memory.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import com.oj.common.core.exception.BusinessException;
import com.oj.judge.memory.LearningMemoryProperties;
import com.oj.judge.memory.mapper.LearningSkillMapper;
import com.oj.judge.memory.model.LearningSkillDO;
import com.oj.judge.memory.skill.search.SkillIndexer;

import cn.hutool.core.lang.Snowflake;

/**
 * 晋升的门槛、幂等与权限（spec D18/D21/D22）。
 *
 * <p>门槛是**证据**问题：三条计数分别来自窗口内不同用户数、窗口内注入数、
 * 窗口内被 AC 归因数。测试直接给这三条计数定值，不去造 30 天的 usage 表。
 */
@ExtendWith(MockitoExtension.class)
class SkillPromotionServiceTest {

    private static final long PERSONAL_ID = 11L;
    private static final long PLATFORM_ID = 22L;
    private static final long OWNER = 7L;
    private static final long ADMIN = 1L;

    @Mock
    private LearningSkillMapper skillMapper;
    @Mock
    private Snowflake snowflake;
    @Mock
    private SkillIndexer skillIndexer;
    @Mock
    private SkillPromotionWriter writer;
    private SkillPromotionService service;

    private LearningSkillDO personal;

    @BeforeEach
    void setUp() {
        LearningMemoryProperties properties = new LearningMemoryProperties();
        // The gates are configurable, so the test states them instead of inheriting defaults.
        properties.setSkillPromotionMinUsers(3);
        properties.setSkillPromotionMinUsage(10);
        properties.setSkillPromotionMinWindowUsage(10);
        properties.setSkillPromotionMinSuccessRate(0.6);
        properties.setSkillPromotionWindowDays(30);
        properties.setSkillPromotionLimit(20);
        service = new SkillPromotionService();
        org.springframework.test.util.ReflectionTestUtils.setField(service, "skillMapper", skillMapper);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "properties", properties);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "snowflake", snowflake);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "skillIndexer", skillIndexer);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "writer", writer);

        personal = new LearningSkillDO();
        personal.setId(PERSONAL_ID);
        personal.setName("二分答案三步走");
        personal.setScope(LearningSkillDO.SCOPE_PERSONAL);
        personal.setOwnerUid(OWNER);
        personal.setStatus(LearningSkillDO.STATUS_ACTIVE);
        personal.setContent("先证明单调性，再二分边界");
        personal.setTags("[\"二分\"]");
        personal.setUsageCount(12);
        personal.setSuccessCount(9);

        lenient().when(skillMapper.selectById(PERSONAL_ID)).thenReturn(personal);
        lenient().when(snowflake.nextId()).thenReturn(PLATFORM_ID);
    }

    private void gate(int users, int windowUsage, int windowSuccess) {
        lenient().when(skillMapper.countWindowUsers(anyLong(), any())).thenReturn(users);
        lenient().when(skillMapper.countWindowUsage(anyLong(), any())).thenReturn(windowUsage);
        lenient().when(skillMapper.countWindowSuccess(anyLong(), any())).thenReturn(windowSuccess);
    }

    @Test
    void promotesWhenEveryGatePasses() {
        gate(3, 12, 9);

        LearningSkillDO promoted = service.promote(ADMIN, PERSONAL_ID);

        assertEquals(PLATFORM_ID, promoted.getId());
        assertEquals(LearningSkillDO.SCOPE_PLATFORM, promoted.getScope());
        assertEquals(LearningSkillDO.STATUS_ACTIVE, promoted.getStatus());
        assertEquals(PERSONAL_ID, promoted.getOriginSkillId());
        assertNull(promoted.getOwnerUid());
        assertEquals("二分答案三步走", promoted.getName());
        assertEquals("先证明单调性，再二分边界", promoted.getContent());
        verify(writer).insertPromoted(eq(promoted), argThatPromotion(ADMIN, PERSONAL_ID));
        verify(skillIndexer).index(promoted);
    }

    @Test
    void thePersonalRowIsNeverModified() {
        gate(3, 12, 9);

        service.promote(ADMIN, PERSONAL_ID);

        // 晋升只新增一行：作者的原行还在，作者仍能改自己的版本。
        verify(skillMapper, never()).updateStatus(anyLong(), any(), any(), any());
        assertEquals(LearningSkillDO.SCOPE_PERSONAL, personal.getScope());
    }

    @Test
    void rejectsWhenTooFewDistinctUsers() {
        gate(2, 12, 9);

        BusinessException error = assertThrows(BusinessException.class, () -> service.promote(ADMIN, PERSONAL_ID));

        assertEquals(40002, error.getCode());
        assertTrue(error.getMessage().contains("不同用户"));
        verify(writer, never()).insertPromoted(any(), any());
    }

    @Test
    void rejectsWhenTheWindowSuccessRateIsTooLow() {
        gate(3, 10, 5);

        BusinessException error = assertThrows(BusinessException.class, () -> service.promote(ADMIN, PERSONAL_ID));

        assertEquals(40002, error.getCode());
        assertTrue(error.getMessage().contains("成功率"));
        verify(writer, never()).insertPromoted(any(), any());
    }

    @Test
    void rejectsWhenLifetimeUsageIsBelowTheGate() {
        personal.setUsageCount(9);
        gate(3, 12, 9);

        assertThrows(BusinessException.class, () -> service.promote(ADMIN, PERSONAL_ID));
        verify(writer, never()).insertPromoted(any(), any());
    }

    @Test
    void theAuthorCannotApproveTheirOwnSkill() {
        gate(3, 12, 9);

        BusinessException error = assertThrows(BusinessException.class, () -> service.promote(OWNER, PERSONAL_ID));

        assertEquals(403, error.getCode());
        verify(writer, never()).insertPromoted(any(), any());
        verify(skillIndexer, never()).index(any());
    }

    @Test
    void promotingTwiceReturnsTheSameRow() {
        gate(3, 12, 9);
        when(skillMapper.selectByOrigin(PERSONAL_ID, LearningSkillDO.SCOPE_PLATFORM))
                .thenReturn(promotedRow());

        LearningSkillDO result = service.promote(ADMIN, PERSONAL_ID);

        assertEquals(PLATFORM_ID, result.getId());
        verify(writer, never()).insertPromoted(any(), any());
        verify(skillIndexer, never()).index(any());
    }

    @Test
    void aConcurrentPromotionCollapsesOntoTheWinner() {
        gate(3, 12, 9);
        when(skillMapper.selectByOrigin(PERSONAL_ID, LearningSkillDO.SCOPE_PLATFORM))
                .thenReturn(null)
                .thenReturn(promotedRow());
        when(writer.insertPromoted(any(), any())).thenThrow(new DuplicateKeyException("uk_origin_scope"));

        LearningSkillDO result = service.promote(ADMIN, PERSONAL_ID);

        assertEquals(PLATFORM_ID, result.getId());
        verify(skillMapper, times(2)).selectByOrigin(PERSONAL_ID, LearningSkillDO.SCOPE_PLATFORM);
        verify(skillIndexer, never()).index(any());
    }

    @Test
    void aDraftOrPlatformSkillCannotBePromoted() {
        personal.setStatus(LearningSkillDO.STATUS_DRAFT);
        BusinessException draft = assertThrows(BusinessException.class, () -> service.promote(ADMIN, PERSONAL_ID));
        assertEquals(40001, draft.getCode());

        personal.setStatus(LearningSkillDO.STATUS_ACTIVE);
        personal.setScope(LearningSkillDO.SCOPE_PLATFORM);
        BusinessException platform = assertThrows(BusinessException.class, () -> service.promote(ADMIN, PERSONAL_ID));
        assertEquals(40001, platform.getCode());

        verify(writer, never()).insertPromoted(any(), any());
    }

    @Test
    void theReportExplainsWhyACandidateIsNotEligible() {
        gate(2, 10, 8);

        SkillPromotionService.Candidate candidate = service.evaluate(personal);

        assertFalse(candidate.isEligible());
        assertTrue(candidate.getRejectReason().contains("不同用户"));
        assertEquals(2, candidate.getWindowUsers());
    }

    @Test
    void scanPromotesEligibleCandidatesAndSkipsTheRest() {
        LearningSkillDO ineligible = new LearningSkillDO();
        ineligible.setId(33L);
        ineligible.setScope(LearningSkillDO.SCOPE_PERSONAL);
        ineligible.setStatus(LearningSkillDO.STATUS_ACTIVE);
        ineligible.setOwnerUid(OWNER);
        ineligible.setUsageCount(12);

        when(skillMapper.selectPromotionCandidates(anyInt(), anyInt())).thenReturn(List.of(PERSONAL_ID, 33L));
        when(skillMapper.selectById(33L)).thenReturn(ineligible);
        gate(3, 12, 9);
        lenient().when(skillMapper.countWindowUsers(eq(33L), any())).thenReturn(1);
        lenient().when(skillMapper.countWindowUsage(eq(33L), any())).thenReturn(12);
        lenient().when(skillMapper.countWindowSuccess(eq(33L), any())).thenReturn(9);

        SkillPromotionService.ScanResult result = service.scan();

        assertEquals(2, result.getCandidates());
        assertEquals(1, result.getPromoted());
        assertEquals(1, result.getSkipped());
        assertEquals(List.of(PLATFORM_ID), result.getPromotedIds());

        ArgumentCaptor<LearningSkillDO> captor = ArgumentCaptor.forClass(LearningSkillDO.class);
        verify(writer).insertPromoted(captor.capture(), argThatPromotion(SkillPromotionService.SYSTEM_ACTOR, PERSONAL_ID));
        assertEquals(PERSONAL_ID, captor.getValue().getOriginSkillId());
    }

    @Test
    void theDailyScanGoesThroughTheTransactionalWriter() {
        when(skillMapper.selectPromotionCandidates(anyInt(), anyInt())).thenReturn(List.of(PERSONAL_ID));
        gate(3, 12, 9);

        service.scan();

        // 每日扫描与手动晋升必须走同一条写路径；`this.promote(...)` 会绕过事务代理，
        // 这条断言就是盯住那个回归的。
        verify(writer).insertPromoted(any(), argThatPromotion(SkillPromotionService.SYSTEM_ACTOR, PERSONAL_ID));
        verify(skillIndexer).index(any());
    }

    @Test
    void windowUsageHasItsOwnGate() {
        // 终身体量够（12），但窗口里只剩 9 次注入：最近没人用了，不该晋升。
        gate(3, 9, 9);

        BusinessException error = assertThrows(BusinessException.class, () -> service.promote(ADMIN, PERSONAL_ID));

        assertEquals(40002, error.getCode());
        assertTrue(error.getMessage().contains("窗口内使用"));
        verify(writer, never()).insertPromoted(any(), any());
    }

    @Test
    void theAuthorCannotReApproveAnAlreadyPromotedSkill() {
        // 幂等不能把「禁止自批」吃掉：作者重复点同一条，仍然必须是 403。
        // 断言方式是「幂等查询一次都没发生」——自批检查排在它前面。
        BusinessException error = assertThrows(BusinessException.class, () -> service.promote(OWNER, PERSONAL_ID));

        assertEquals(403, error.getCode());
        verify(skillMapper, never()).selectByOrigin(anyLong(), any());
        verify(writer, never()).insertPromoted(any(), any());
    }

    @Test
    void scanIsOffWhenTheFeatureIsDisabled() {
        LearningMemoryProperties properties = new LearningMemoryProperties();
        properties.setSkillPromotionEnabled(false);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "properties", properties);

        SkillPromotionService.ScanResult result = service.scan();

        assertEquals(0, result.getCandidates());
        verify(skillMapper, never()).selectPromotionCandidates(anyInt(), anyInt());
    }

    /** 晋升上下文的断言：actor 与 origin 都是 Long，换位不会报错，只能靠断言钉住顺序。 */
    private static PromotionContext argThatPromotion(long actorUid, long originId) {
        return argThat(context -> context != null
                && context.actorUid() == actorUid
                && context.originId() == originId);
    }

    private static LearningSkillDO promotedRow() {
        LearningSkillDO row = new LearningSkillDO();
        row.setId(PLATFORM_ID);
        row.setOriginSkillId(PERSONAL_ID);
        row.setScope(LearningSkillDO.SCOPE_PLATFORM);
        row.setStatus(LearningSkillDO.STATUS_ACTIVE);
        return row;
    }
}
