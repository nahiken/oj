package com.oj.judge.memory.episode;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.oj.judge.memory.model.LearningEventDO;

/**
 * 会话切分是纯函数：同一输入必得同一输出，且边界（恰好等于 gap）不能切。
 */
class SessionSplitterTest {

    private static final long GAP = 30 * 60 * 1000L;

    @Test
    void eventsWithinGapStayInOneSession() {
        List<SessionSplitter.Session> sessions = SessionSplitter.split(
                events(0, 10 * 60 * 1000L, 29 * 60 * 1000L), GAP);
        assertEquals(1, sessions.size());
        assertEquals(3, sessions.get(0).size());
    }

    @Test
    void gapIsExclusiveBoundary() {
        // 恰好 30 分钟不算「超过」，仍是同一会话；超过 1ms 才切
        assertEquals(1, SessionSplitter.split(events(0, GAP), GAP).size());
        assertEquals(2, SessionSplitter.split(events(0, GAP + 1), GAP).size());
    }

    @Test
    void longSilenceStartsNewSession() {
        List<SessionSplitter.Session> sessions = SessionSplitter.split(
                events(0, 1000L, 2 * GAP, 2 * GAP + 1000L), GAP);
        assertEquals(2, sessions.size());
        assertEquals(2, sessions.get(0).size());
        assertEquals(2, sessions.get(1).size());
    }

    @Test
    void sessionBoundsComeFromFirstAndLastEvent() {
        List<SessionSplitter.Session> sessions = SessionSplitter.split(events(5000L, 7000L), GAP);
        assertEquals(new Date(5000L), sessions.get(0).startedAt());
        assertEquals(new Date(7000L), sessions.get(0).endedAt());
    }

    @Test
    void emptyInputYieldsNoSession() {
        assertEquals(0, SessionSplitter.split(new ArrayList<>(), GAP).size());
        assertEquals(0, SessionSplitter.split(null, GAP).size());
    }

    private List<LearningEventDO> events(long... times) {
        List<LearningEventDO> events = new ArrayList<>();
        for (long time : times) {
            LearningEventDO event = new LearningEventDO();
            event.setId(time + 1);
            event.setOccurredAt(new Date(time));
            event.setEventType("submit");
            events.add(event);
        }
        return events;
    }
}
