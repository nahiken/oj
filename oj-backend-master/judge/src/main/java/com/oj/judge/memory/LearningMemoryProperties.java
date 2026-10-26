package com.oj.judge.memory;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

/**
 * 学习记忆体配置。契约见 docs/design-docs/0004-learning-memory.md。
 *
 * <p>前缀：{@code oj.memory}
 */
@Data
@Component
@ConfigurationProperties(prefix = "oj.memory")
public class LearningMemoryProperties {

    /** 总开关：false 时 Recorder 空操作、聚合任务不注册、MQ 拓扑不声明 */
    private boolean enabled = true;

    /** amqp | direct。direct = 不经过 MQ，事件在调用线程直接写库（最小化部署/降级） */
    private String transport = "amqp";
    /** 会话切分间隔（毫秒）：相邻事件超过它就算新会话 */
    private long episodeGapMs = 1800000L;

    /** Episode 聚合调度周期（毫秒） */
    private long episodeIntervalMs = 60000L;

    /** Pattern 重算周期（毫秒） */
    private long patternIntervalMs = 600000L;

    /** 聚合窗口回看天数（毫秒查询的 where 下界） */
    private int lookbackDays = 7;

    /** 单轮聚合处理的分组键上限 */
    private int batchKeys = 200;

    /** 单会话最多读取的事件数，防御异常账号 */
    private int maxEventsPerSession = 500;

    /** AC 快速通道：收到 ac 立刻物化该题会话，不等静默 gap */
    private boolean acFastPath = true;

    /** Context 组装的 Episode / Pattern 条数 */
    private int contextEpisodes = 3;
    private int contextPatterns = 3;

    /** Context token 预算（启发式估算，非分词器实测） */
    private int contextTokenBudget = 1200;

    /** 单条事件 payload 上限（字节） */
    private int payloadMaxBytes = 2048;

    /** 客户端上报限流：每 uid 每分钟条数（超限丢弃，不报错） */
    private int clientEventsPerMinute = 600;

    /** Pattern 统计窗口（天） */
    private int patternWindowDays = 30;

    /** 单用户参与 Pattern 统计的 Episode 上限 */
    private int patternMaxEpisodes = 200;

    // ---- Skill 晋升（spec D21 / D23）----

    /** 每日扫描开关。false 时只保留手动 `POST /learning/skill/{id}/promote` */
    private boolean skillPromotionEnabled = true;

    // 扫描时间不在这里：cron 由 SkillPromotionJob 的 @Scheduled 直接读
    // `oj.memory.skill-promotion-cron`，同一个配置只留一处事实来源。

    /** 单次扫描最多晋升多少条（按 usage_count 降序） */
    private int skillPromotionLimit = 20;

    /** 证据窗口（天）：不同 uid 数与窗口成功率都在这个窗口里算 */
    private int skillPromotionWindowDays = 30;

    /** 门槛：至少几个不同用户用过 */
    private int skillPromotionMinUsers = 3;

    /** 门槛：终身 usage_count 至少多少（单调计数器，不会因窗口滚动而缩水） */
    private int skillPromotionMinUsage = 10;

    /**
     * 门槛：**窗口内**注入次数至少多少。
     *
     * <p>和 {@link #skillPromotionMinUsage} 分开：那个是终身体量，这个是「最近还在被用」。
     * 合成一个会让「半年前火过、现在没人用」的 Skill 一直够格。
     */
    private int skillPromotionMinWindowUsage = 10;

    /** 门槛：窗口成功率（rewarded / 窗口内注入次数） */
    private double skillPromotionMinSuccessRate = 0.6;

    public boolean isAmqp() {
        return "amqp".equalsIgnoreCase(transport);
    }
}
