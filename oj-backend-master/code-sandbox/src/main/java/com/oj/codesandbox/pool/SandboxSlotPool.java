package com.oj.codesandbox.pool;

import java.util.Map;

/**
 * 沙箱槽位池。实现负责借约、归还清理、健康检查与重建。
 *
 * <p>契约：{@code docs/design-docs/0002-judge-pool.md} §3。
 */
public interface SandboxSlotPool {

    /**
     * 借一个可用槽位；池耗尽时阻塞到 {@code borrow-timeout-ms} 后抛 {@link SandboxBusyException}。
     */
    SandboxSlot borrow();

    /**
     * 归还槽位。
     *
     * @param reusable false 表示本次执行污染了槽位（异常/超时残留），直接销毁重建
     */
    void release(SandboxSlot slot, boolean reusable);

    /** 指标快照。 */
    Map<String, Object> snapshot();

    /** native / docker。 */
    String backend();
}
