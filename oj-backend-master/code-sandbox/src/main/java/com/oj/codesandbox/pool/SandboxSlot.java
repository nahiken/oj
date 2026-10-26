package com.oj.codesandbox.pool;

import java.nio.file.Path;

/**
 * 一次执行借到的工作区。
 *
 * <p>native 后端只有宿主目录；docker 后端还有绑定该目录的常驻容器。
 * 池只关心「借、还、丢弃、重建」，语言与镜像由 {@link SandboxSlotFactory} 决定。
 *
 * <p>契约：{@code docs/design-docs/0002-judge-pool.md} §3。
 */
public interface SandboxSlot {

    /** 池内编号（pool-N / ephemeral-N），用于日志与指标。 */
    String id();

    /** 宿主工作区目录；docker 后端会把它 bind 到容器 /app。 */
    Path workspace();

    /** docker 后端的容器 ID；native 返回 null。 */
    String containerId();

    /** 借出次数，用于复用上限。 */
    int useCount();

    /** 借出时由池调用。 */
    void markUsed();

    /** 借约前的健康检查，要求便宜（每次借出都会调）：容器在跑 / 目录在。 */
    boolean healthy();

    /**
     * 深度健康检查：比 {@link #healthy()} 贵一个数量级，只在**周期巡检**与**槽位创建后**调用。
     *
     * <p>docker 后端在这里做容器内写探针——inspect 只能证明容器在跑，证明不了 bind mount
     * 还能写；宿主目录可写也不等于容器内可见。native 后端的 healthy() 本身就是写探针，
     * 直接复用。
     */
    default boolean probe() {
        return healthy();
    }

    /** 清理到可再次执行。失败表示该槽位不应再被复用。 */
    void reset();

    /** 释放资源，之后不可再用。 */
    void destroy();
}
