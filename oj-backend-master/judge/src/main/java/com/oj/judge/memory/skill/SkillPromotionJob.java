package com.oj.judge.memory.skill;

import javax.annotation.Resource;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.oj.judge.memory.LearningMemoryProperties;

import lombok.extern.slf4j.Slf4j;

/**
 * 每日 03:00 的晋升扫描（spec D23）。
 *
 * <p>手动的 `POST /learning/skill/{id}/promote` 走同一个 {@link SkillPromotionService}，
 * 所以「扫描自动晋升」和「管理员点一条」用的是同一套门槛、同一套幂等、同一套审计。
 * 扫描的 actor 是 {@link SkillPromotionService#SYSTEM_ACTOR}（0），审计里能看出不是人批的。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "oj.memory", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SkillPromotionJob {

    @Resource
    private SkillPromotionService promotionService;
    @Resource
    private LearningMemoryProperties properties;

    @Scheduled(cron = "${oj.memory.skill-promotion-cron:0 0 3 * * ?}")
    public void daily() {
        if (!properties.isSkillPromotionEnabled()) {
            return;
        }
        SkillPromotionService.ScanResult result = promotionService.scan();
        log.info("evt=memory.skill.daily candidates={} promoted={} skipped={}", result.getCandidates(),
                result.getPromoted(), result.getSkipped());
    }
}
