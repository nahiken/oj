package com.oj.judge.statemachine;

import java.util.Map;

import lombok.Data;

/**
 * Redis 中的提交状态快照（judge:state:{submitId}）。
 *
 * <p>契约：docs/design-docs/0001-async-judge.md §6.2。
 */
@Data
public class JudgeState {

    private long submitId;
    private int status;
    private int version;
    private int attempt;
    private int epoch;
    private long uid;
    private long problemId;
    private String workerId;
    private String judgeInfo;
    private long leaseUntil;
    private long updatedAt;

    public boolean isTerminal() {
        return status >= 2;
    }

    public static JudgeState fromHash(long submitId, Map<Object, Object> hash) {
        if (hash == null || hash.isEmpty()) {
            return null;
        }
        JudgeState state = new JudgeState();
        state.setSubmitId(submitId);
        state.setStatus(parseInt(hash.get("status")));
        state.setVersion(parseInt(hash.get("version")));
        state.setAttempt(parseInt(hash.get("attempt")));
        state.setEpoch(parseInt(hash.get("epoch")));
        state.setUid(parseLong(hash.get("uid")));
        state.setProblemId(parseLong(hash.get("problemId")));
        state.setWorkerId(asString(hash.get("workerId")));
        state.setJudgeInfo(asString(hash.get("ji")));
        state.setLeaseUntil(parseLong(hash.get("leaseUntil")));
        state.setUpdatedAt(parseLong(hash.get("updatedAt")));
        return state;
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    private static int parseInt(Object value) {
        try {
            return value == null ? 0 : Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static long parseLong(Object value) {
        try {
            return value == null ? 0L : Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
