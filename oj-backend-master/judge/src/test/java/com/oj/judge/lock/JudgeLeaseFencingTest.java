package com.oj.judge.lock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import com.oj.judge.config.JudgeAsyncProperties;
import com.oj.judge.metrics.JudgeMetrics;
import com.oj.judge.model.enums.ProblemSubmitStatusEnum;
import com.oj.judge.statemachine.JudgeState;
import com.oj.judge.statemachine.JudgeStateService;

/**
 * 租约与 fencing 的契约测试（真实 Redis、真实 Lua）。
 *
 * <p>这是判题链路的第一组单测：状态机的关键不变量（旧 epoch 不能写、过期快照不能重置存活认领、
 * epoch 不能回退、锁不能被误删、续租只属于持有者）必须能脱离 Spring 上下文与 MQ 单独验证。
 * Redis 不可用时整类跳过（assumption），不把「环境缺失」伪装成「通过」。
 *
 * <p>契约：docs/design-docs/0003-lease-fencing.md §2、§4、§5。
 */
class JudgeLeaseFencingTest {

    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate redis;
    private static boolean redisAvailable;

    private JudgeStateService stateService;
    private JudgeLeaseManager leaseManager;
    private long submitId;

    @BeforeAll
    static void connect() {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration("127.0.0.1", 6379);
        config.setDatabase(15);
        factory = new LettuceConnectionFactory(config);
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
        try {
            redis.execute((org.springframework.data.redis.core.RedisCallback<String>) connection -> connection.ping());
            redisAvailable = true;
        } catch (Exception e) {
            redisAvailable = false;
        }
    }

    @BeforeEach
    void setUp() {
        Assumptions.assumeTrue(redisAvailable, "Redis 127.0.0.1:6379/15 不可用，跳过租约契约测试");

        JudgeAsyncProperties properties = new JudgeAsyncProperties();
        properties.getAsync().setStateTtlSeconds(300);
        properties.getAsync().setLeaseTtlMs(3000L);
        properties.getAsync().setLeaseRenewIntervalMs(1000L);
        properties.getAsync().setLeaseRenewEnabled(true);

        JudgeStateService state = new JudgeStateService();
        ReflectionTestUtils.setField(state, "stringRedisTemplate", redis);
        ReflectionTestUtils.setField(state, "properties", properties);
        ReflectionTestUtils.setField(state, "judgeInitScript", script("lua/judge_init.lua"));
        ReflectionTestUtils.setField(state, "judgeTransitionScript", script("lua/judge_transition.lua"));
        ReflectionTestUtils.setField(state, "judgeUnlockScript", script("lua/judge_unlock.lua"));
        ReflectionTestUtils.setField(state, "judgeNextEpochScript", script("lua/judge_next_epoch.lua"));
        stateService = state;

        JudgeLeaseManager manager = new JudgeLeaseManager();
        ReflectionTestUtils.setField(manager, "stringRedisTemplate", redis);
        ReflectionTestUtils.setField(manager, "properties", properties);
        ReflectionTestUtils.setField(manager, "stateService", state);
        ReflectionTestUtils.setField(manager, "metrics", new JudgeMetrics());
        ReflectionTestUtils.setField(manager, "judgeLeaseRenewScript", script("lua/judge_lease_renew.lua"));
        leaseManager = manager;

        submitId = System.nanoTime();
        stateService.initIfAbsent(submitId, 7L, 11L, "{}");
    }

    @AfterEach
    void tearDown() {
        if (!redisAvailable) {
            return;
        }
        redis.delete(List.of(stateService.stateKey(submitId), stateService.lockKey(submitId),
                stateService.epochKey(submitId)));
    }

    @Test
    void staleEpochCannotWriteTerminalState() {
        long staleEpoch = 1L;
        assertEquals(JudgeStateService.OK, claim(staleEpoch));

        // 接管：把 epoch 推进到 2，状态回 WAITING（旧执行者从此被 fence）
        assertEquals(JudgeStateService.OK, takeover(staleEpoch, 2L));
        // 新持有者认领：状态重新是 RUNNING，所以旧执行者的终态写入会走到 epoch 校验而不是状态校验
        assertEquals(JudgeStateService.OK, claim(3L));

        long stale = stateService.transition(submitId, ProblemSubmitStatusEnum.RUNNING.getValue(), -1,
                ProblemSubmitStatusEnum.SUCCEED.getValue(), null, null, "{\"stale\":true}", null,
                staleEpoch, null);
        assertEquals(JudgeStateService.FENCED, stale, "旧 epoch 写终态必须被拒绝");

        JudgeState afterFence = stateService.getState(submitId);
        assertEquals(ProblemSubmitStatusEnum.RUNNING.getValue(), afterFence.getStatus());
        assertEquals(3, afterFence.getVersion());

        // 新持有者可以写
        long fresh = stateService.transition(submitId, ProblemSubmitStatusEnum.RUNNING.getValue(), -1,
                ProblemSubmitStatusEnum.SUCCEED.getValue(), null, null, "{\"fresh\":true}", null, 3L, null);
        assertEquals(JudgeStateService.OK, fresh);
        assertEquals(ProblemSubmitStatusEnum.SUCCEED.getValue(), stateService.getState(submitId).getStatus());
    }

    /**
     * P9：读取 RUNNING(epoch=1) 的过期快照，不能重置「别人刚接管并认领」的活任务。
     *
     * <p>复现路径：A 拿快照 → B 接管（epoch=2）并重新认领（epoch=3，状态又是 RUNNING）→
     * A 用 epoch=1 做 RUNNING→WAITING。修复后传 expectedEpoch=1，Lua 返回 3，状态不动。
     */
    @Test
    void staleRunningSnapshotCannotResetLiveClaim() {
        assertEquals(JudgeStateService.OK, claim(1L));
        assertEquals(JudgeStateService.OK, takeover(1L, 2L));
        assertEquals(JudgeStateService.OK, claim(3L));
        JudgeState before = stateService.getState(submitId);
        assertEquals(ProblemSubmitStatusEnum.RUNNING.getValue(), before.getStatus());
        assertEquals(3, before.getEpoch());

        // 与修复后的 JudgeWorker.handleRunningState 同参数：expectedEpoch=读到的这一代
        long nextEpoch = stateService.nextEpoch(submitId);
        long result = stateService.transition(submitId, ProblemSubmitStatusEnum.RUNNING.getValue(), -1,
                ProblemSubmitStatusEnum.WAITING.getValue(), null, 2, null, null, 1L, nextEpoch);
        assertEquals(JudgeStateService.FENCED, result, "过期 RUNNING 快照的重置必须被 fence");

        JudgeState after = stateService.getState(submitId);
        assertEquals(ProblemSubmitStatusEnum.RUNNING.getValue(), after.getStatus());
        assertEquals(before.getVersion(), after.getVersion(), "被 fence 时版本号不能变化");
        assertEquals(3, after.getEpoch(), "被 fence 时 epoch 不能变化");
    }

    /**
     * P10：{@code judge:epoch:*} 丢失后，nextEpoch 必须跳过状态哈希里的值；
     * transition 也不能把 epoch 写小。
     */
    @Test
    void epochCannotGoBackwardsWhenEpochKeyIsLost() {
        assertEquals(JudgeStateService.OK, claim(5L));
        redis.delete(stateService.epochKey(submitId));
        assertNull(redis.opsForValue().get(stateService.epochKey(submitId)));

        long next = stateService.nextEpoch(submitId);
        assertEquals(6L, next, "删掉 judge:epoch:* 后 nextEpoch 必须从状态值 5 之后继续");

        // 写回退值 4 必须被 fence（newEpoch <= curEpoch）
        long backwards = stateService.transition(submitId, ProblemSubmitStatusEnum.RUNNING.getValue(), -1,
                ProblemSubmitStatusEnum.WAITING.getValue(), null, 2, null, null, null, 4L);
        assertEquals(JudgeStateService.FENCED, backwards, "epoch 不能回退");
        assertEquals(5, stateService.getState(submitId).getEpoch());

        // 推进值 6 可以写
        long forward = stateService.transition(submitId, ProblemSubmitStatusEnum.RUNNING.getValue(), -1,
                ProblemSubmitStatusEnum.WAITING.getValue(), null, 2, null, null, 5L, next);
        assertEquals(JudgeStateService.OK, forward);
        assertEquals(6, stateService.getState(submitId).getEpoch());
    }

    @Test
    void acquireIsExclusiveAndEpochIsMonotonic() {
        JudgeLease first = leaseManager.acquire(submitId, "worker-a");
        assertNotNull(first);
        assertEquals(ClaimToken.of("worker-a", first.getEpoch()).value(), first.getToken());
        assertTrue(leaseManager.renew(first), "持有者应能续租");

        assertNull(leaseManager.acquire(submitId, "worker-b"), "锁被持有时第二个执行者必须抢不到");

        leaseManager.release(first);
        JudgeLease second = leaseManager.acquire(submitId, "worker-b");
        assertNotNull(second);
        assertTrue(second.getEpoch() > first.getEpoch(), "每次认领都要推进 epoch");
        assertFalse(leaseManager.renew(first), "旧租约已释放，不能再续租");
    }

    @Test
    void renewExtendsLeaseAndChecksToken() {
        JudgeLease lease = leaseManager.acquire(submitId, "worker-a");
        assertNotNull(lease);
        leaseManager.watch(lease);

        assertTrue(leaseManager.renew(lease));
        long extended = redis.getExpire(stateService.lockKey(submitId));
        assertTrue(extended > 0, "续租后锁必须有 TTL");

        JudgeLease forged = new JudgeLease(submitId, "worker-a", lease.getToken() + "x", lease.getEpoch());
        assertFalse(leaseManager.renew(forged), "token 不匹配不能续租");
        assertTrue(forged.isLost());

        leaseManager.release(lease);
        assertFalse(Boolean.TRUE.equals(redis.hasKey(stateService.lockKey(submitId))));
    }

    private long claim(long epoch) {
        return stateService.transition(submitId, ProblemSubmitStatusEnum.WAITING.getValue(), -1,
                ProblemSubmitStatusEnum.RUNNING.getValue(), "worker-a", 1, null,
                System.currentTimeMillis() + 3000L, null, epoch);
    }

    private long takeover(long expectedEpoch, long newEpoch) {
        return stateService.transition(submitId, ProblemSubmitStatusEnum.RUNNING.getValue(), -1,
                ProblemSubmitStatusEnum.WAITING.getValue(), null, 2, null, null, expectedEpoch, newEpoch);
    }

    private static DefaultRedisScript<Long> script(String location) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(location));
        script.setResultType(Long.class);
        return script;
    }
}
