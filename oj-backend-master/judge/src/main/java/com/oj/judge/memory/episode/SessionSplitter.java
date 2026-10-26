package com.oj.judge.memory.episode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import com.oj.judge.memory.model.LearningEventDO;

/**
 * 会话切分：同一 (uid, problem_id) 的事件里，相邻间隔超过 gap 就切成新会话（契约 0004 §4.1）。
 *
 * <p>纯函数、无依赖，便于单测与「同一输入必得同一输出」的复查。
 */
public final class SessionSplitter {

    private SessionSplitter() {
    }

    public static List<Session> split(List<LearningEventDO> events, long gapMs) {
        if (events == null || events.isEmpty()) {
            return Collections.emptyList();
        }
        long gap = Math.max(1L, gapMs);
        List<Session> sessions = new ArrayList<>();
        List<LearningEventDO> current = new ArrayList<>();
        long previous = Long.MIN_VALUE;
        for (LearningEventDO event : events) {
            long time = occurredAt(event);
            if (!current.isEmpty() && time - previous > gap) {
                sessions.add(new Session(current));
                current = new ArrayList<>();
            }
            current.add(event);
            previous = time;
        }
        if (!current.isEmpty()) {
            sessions.add(new Session(current));
        }
        return sessions;
    }

    private static long occurredAt(LearningEventDO event) {
        Date at = event.getOccurredAt();
        return at == null ? 0L : at.getTime();
    }

    /**
     * 一个会话：事件按时间升序，首尾即 startedAt / endedAt。
     */
    public static final class Session {

        private final List<LearningEventDO> events;

        Session(List<LearningEventDO> events) {
            this.events = Collections.unmodifiableList(new ArrayList<>(events));
        }

        public List<LearningEventDO> events() {
            return events;
        }

        public int size() {
            return events.size();
        }

        public Date startedAt() {
            return events.get(0).getOccurredAt();
        }

        public Date endedAt() {
            return events.get(events.size() - 1).getOccurredAt();
        }
    }
}
