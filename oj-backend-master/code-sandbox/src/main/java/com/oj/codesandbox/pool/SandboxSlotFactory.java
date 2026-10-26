package com.oj.codesandbox.pool;

/**
 * 创建 slot 的后端：native 只建目录，docker 还要建常驻容器。
 */
public interface SandboxSlotFactory {

    SandboxSlot create(String slotId);

    /** 指标里的后端名：native / docker。 */
    String backend();
}
