package com.oj.judge.memory.mapper;

import org.apache.ibatis.annotations.Insert;

import com.oj.judge.memory.model.AiTutoringSessionDO;

/**
 * `ai_tutoring_session` 的写入口（P7 票 01）。
 *
 * <p>只写不改：会话是既成事实，没有「回滚一次辅导」的语义。
 */
public interface AiTutoringSessionMapper {

    @Insert("INSERT INTO ai_tutoring_session (id, uid, problem_id, level, session_id, started_at, "
            + "finished_at, finish_reason, prompt_tokens, completion_tokens, search_used) "
            + "VALUES (#{id}, #{uid}, #{problemId}, #{level}, #{sessionId}, #{startedAt}, #{finishedAt}, "
            + "#{finishReason}, #{promptTokens}, #{completionTokens}, #{searchUsed})")
    int insert(AiTutoringSessionDO session);
}
