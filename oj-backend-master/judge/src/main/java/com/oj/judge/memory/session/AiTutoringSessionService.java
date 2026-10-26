package com.oj.judge.memory.session;

import java.util.Date;
import java.util.Set;

import javax.annotation.Resource;

import org.springframework.stereotype.Service;

import com.oj.judge.memory.dto.AiTutoringSessionReq;
import com.oj.judge.memory.mapper.AiTutoringSessionMapper;
import com.oj.judge.memory.model.AiTutoringSessionDO;

import cn.hutool.core.lang.Snowflake;
import lombok.extern.slf4j.Slf4j;

/**
 * 辅导会话落库（P7 票 01）。
 *
 * <p>这条链路是**旁路**：AI 辅导流不因为这里失败而失败。所以校验不通过、库写不进去
 * 都返回 {@code false} 并记一行日志，绝不向上抛——用户正在读的回答比统计更重要。
 *
 * <p>但也正因为它只进不出，写进去的行必须是干净的：`finishReason` 只认契约里的五个值，
 * 层级只认 1/2/3，结束时间不能早于开始时间。宁可少一行，也不要一行语义错误的行
 * 把 二次 AC 率 的分母污染掉。
 */
@Slf4j
@Service
public class AiTutoringSessionService {

    /** 与 `docs/design-docs/0006-ai-tutor.md` §11 及 Python 侧 `session_record.FINISH_REASONS` 一致。 */
    public static final Set<String> FINISH_REASONS = Set.of("stop", "guarded", "length", "error", "aborted");

    @Resource
    private AiTutoringSessionMapper sessionMapper;
    @Resource
    private Snowflake snowflake;

    public boolean record(Long uid, AiTutoringSessionReq req) {
        if (!isValid(uid, req)) {
            return false;
        }
        AiTutoringSessionDO row = new AiTutoringSessionDO();
        row.setId(snowflake.nextId());
        row.setUid(uid);
        row.setProblemId(req.getProblemId());
        row.setLevel(req.getLevel());
        row.setSessionId(blankToNull(req.getSessionId()));
        row.setStartedAt(Date.from(req.getStartedAt().toInstant()));
        row.setFinishedAt(Date.from(req.getFinishedAt().toInstant()));
        row.setFinishReason(req.getFinishReason());
        row.setPromptTokens(req.getPromptTokens());
        row.setCompletionTokens(req.getCompletionTokens());
        row.setSearchUsed(Boolean.TRUE.equals(req.getSearchUsed()));
        try {
            return sessionMapper.insert(row) == 1;
        } catch (RuntimeException ex) {
            // 旁路写入：库抖动不应该让用户看到一次失败的辅导。
            log.warn("evt=ai.session.persist-failed uid={} problemId={} reason={} err={}", uid,
                    req.getProblemId(), req.getFinishReason(), ex.getMessage());
            return false;
        }
    }

    private boolean isValid(Long uid, AiTutoringSessionReq req) {
        if (uid == null || req == null) {
            return false;
        }
        if (req.getProblemId() == null || req.getProblemId() <= 0) {
            return false;
        }
        if (req.getLevel() == null || req.getLevel() < 1 || req.getLevel() > 3) {
            return false;
        }
        if (req.getSessionId() != null && req.getSessionId().length() > 64) {
            // 列是 VARCHAR(64)：超长会让整条 INSERT 失败，这里先挡掉并留日志。
            return false;
        }
        // Set.of(...).contains(null) throws NPE, which would turn a malformed
        // callback into a 500 instead of the documented "drop the row".
        if (req.getFinishReason() == null || !FINISH_REASONS.contains(req.getFinishReason())) {
            return false;
        }
        if (req.getStartedAt() == null || req.getFinishedAt() == null) {
            return false;
        }
        return !req.getFinishedAt().isBefore(req.getStartedAt());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
