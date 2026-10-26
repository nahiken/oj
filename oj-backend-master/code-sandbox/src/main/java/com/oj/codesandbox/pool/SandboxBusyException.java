package com.oj.codesandbox.pool;

/**
 * 池在借约超时后抛出：判题侧应把它当成「暂时繁忙」，而不是「代码有问题」。
 */
public class SandboxBusyException extends RuntimeException {

    public SandboxBusyException(String message) {
        super(message);
    }
}
