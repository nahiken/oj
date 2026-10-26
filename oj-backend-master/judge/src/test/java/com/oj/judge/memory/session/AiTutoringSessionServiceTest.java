package com.oj.judge.memory.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.oj.judge.memory.dto.AiTutoringSessionReq;
import com.oj.judge.memory.mapper.AiTutoringSessionMapper;
import com.oj.judge.memory.model.AiTutoringSessionDO;

import cn.hutool.core.lang.Snowflake;

/**
 * 辅导会话落库：只接受契约里的 finishReason，坏数据静默丢弃而不是抛给流。
 *
 * <p>记录的用途是 二次 AC 率 的分母，所以宁可少一行，也不能写一行语义错误的。
 */
@ExtendWith(MockitoExtension.class)
class AiTutoringSessionServiceTest {

    private static final OffsetDateTime START = OffsetDateTime.parse("2026-10-21T10:00:00+08:00");
    private static final OffsetDateTime END = OffsetDateTime.parse("2026-10-21T10:00:05.500+08:00");

    @Mock
    private AiTutoringSessionMapper sessionMapper;
    @Mock
    private Snowflake snowflake;
    @InjectMocks
    private AiTutoringSessionService service;

    private static AiTutoringSessionReq req(String finishReason) {
        AiTutoringSessionReq req = new AiTutoringSessionReq();
        req.setProblemId(7L);
        req.setLevel(2);
        req.setSessionId("s-1");
        req.setStartedAt(START);
        req.setFinishedAt(END);
        req.setFinishReason(finishReason);
        req.setPromptTokens(812);
        req.setCompletionTokens(140);
        req.setSearchUsed(true);
        return req;
    }

    @Test
    void aValidSessionIsPersistedWithTheCallersUid() {
        when(snowflake.nextId()).thenReturn(1001L);
        when(sessionMapper.insert(any(AiTutoringSessionDO.class))).thenReturn(1);

        assertTrue(service.record(99L, req("stop")));

        ArgumentCaptor<AiTutoringSessionDO> captor = ArgumentCaptor.forClass(AiTutoringSessionDO.class);
        verify(sessionMapper).insert(captor.capture());
        AiTutoringSessionDO row = captor.getValue();
        assertNotNull(row.getId());
        assertEquals(99L, row.getUid(), "uid comes from the token, never from the body");
        assertEquals(7L, row.getProblemId());
        assertEquals(2, row.getLevel());
        assertEquals("stop", row.getFinishReason());
        assertEquals(812, row.getPromptTokens());
        assertEquals(140, row.getCompletionTokens());
        assertTrue(row.getSearchUsed());
        assertEquals(START.toInstant(), row.getStartedAt().toInstant());
        assertEquals(END.toInstant(), row.getFinishedAt().toInstant());
    }

    @Test
    void anUnknownFinishReasonIsDropped() {
        assertFalse(service.record(99L, req("finished")));
        verify(sessionMapper, never()).insert(any(AiTutoringSessionDO.class));
    }

    @Test
    void aMissingFinishReasonIsDroppedInsteadOfThrowing() {
        // Set.of(...).contains(null) throws; the endpoint must answer "rejected",
        // not 500, because the callback is best-effort.
        assertFalse(service.record(99L, req(null)));
        verify(sessionMapper, never()).insert(any(AiTutoringSessionDO.class));
    }

    @Test
    void aLevelOutsideTheLadderIsDropped() {
        AiTutoringSessionReq req = req("stop");
        req.setLevel(4);
        assertFalse(service.record(99L, req));
        verify(sessionMapper, never()).insert(any(AiTutoringSessionDO.class));
    }

    @Test
    void aSessionWithoutAUserOrAProblemIsDropped() {
        assertFalse(service.record(null, req("stop")));
        AiTutoringSessionReq noProblem = req("stop");
        noProblem.setProblemId(0L);
        assertFalse(service.record(99L, noProblem));
        verify(sessionMapper, never()).insert(any(AiTutoringSessionDO.class));
    }

    @Test
    void aSessionThatEndsBeforeItStartsIsDropped() {
        AiTutoringSessionReq req = req("stop");
        req.setFinishedAt(START.minusSeconds(1));
        assertFalse(service.record(99L, req));
        verify(sessionMapper, never()).insert(any(AiTutoringSessionDO.class));
    }

    @Test
    void anInsertFailureIsReportedAsFalseInsteadOfThrown() {
        when(snowflake.nextId()).thenReturn(1002L);
        when(sessionMapper.insert(any(AiTutoringSessionDO.class))).thenThrow(new RuntimeException("db down"));
        assertFalse(service.record(99L, req("guarded")));
    }

    @Test
    void anInsertThatAffectsNoRowIsReportedAsFalse() {
        when(snowflake.nextId()).thenReturn(1003L);
        when(sessionMapper.insert(any(AiTutoringSessionDO.class))).thenReturn(0);
        assertFalse(service.record(99L, req("error")));
    }

    @Test
    void aSessionIdLongerThanTheColumnIsDropped() {
        AiTutoringSessionReq req = req("stop");
        req.setSessionId("x".repeat(65));
        assertFalse(service.record(99L, req));
        verify(sessionMapper, never()).insert(any(AiTutoringSessionDO.class));
    }
}
