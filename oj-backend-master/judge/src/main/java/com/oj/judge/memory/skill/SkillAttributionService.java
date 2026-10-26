package com.oj.judge.memory.skill;

import java.util.Date;
import java.util.List;

import javax.annotation.Resource;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.oj.judge.memory.event.LearningAcceptedEvent;
import com.oj.judge.memory.mapper.LearningSkillMapper;
import com.oj.judge.memory.model.LearningSkillUsageDO;

/**
 * success_count 归因（spec D11）。
 *
 * <p>规则：一次 AC 之后，回看该用户在该题上**10 分钟内**被注入过、且还没归因的 Skill，
 * 各 +1。只认「真正注入过」的 Skill，不是「列表里出现过」。
 *
 * <p>幂等靠 `rewarded` 的条件更新：同一行只会被一次 AC 认领，重复的 AC 不会重复加。
 */
@Service
public class SkillAttributionService {

    static final long WINDOW_MS = 10 * 60 * 1000L;

    @Resource
    private LearningSkillMapper skillMapper;

    @EventListener
    @Transactional
    public void onAccepted(LearningAcceptedEvent event) {
        if (event.getUid() == null || event.getProblemId() == null || event.getProblemId() <= 0) {
            return;
        }
        Date since = new Date(System.currentTimeMillis() - WINDOW_MS);
        List<LearningSkillUsageDO> usages =
                skillMapper.selectRewardable(event.getUid(), event.getProblemId(), since);
        for (LearningSkillUsageDO usage : usages) {
            // 先认领再计数：并发 AC 时只有一行更新成功，success_count 不会被加两次。
            if (skillMapper.markRewarded(usage.getId()) == 1) {
                skillMapper.incrementSuccess(usage.getSkillId());
            }
        }
    }
}
