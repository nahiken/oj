package com.oj.judge.executor;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import com.oj.judge.config.JudgeAsyncProperties;
import com.oj.judge.mq.JudgeMqConstants;

import lombok.extern.slf4j.Slf4j;

/**
 * 加权公平 + 工作 conserving 的判题调度器。
 *
 * <p>为什么不是「每档一个固定线程池」：静态 4:2:1 的消费者数量在只有 NORMAL 流量时会把
 * 4 个 HIGH 线程白白空转，实测把判题吞吐打到 1.4/s（低于 P0 基线）。
 *
 * <p>做法：三个有界档位队列 + 一个派发线程 + 固定大小工作线程池。
 * 派发线程按 {@code H H H H N N R} 的加权模式取任务，取到空档位就顺延到下一档，
 * 所以（1）两档都有积压时高优获得 4:2:1 的派发份额；（2）只有一档有流量时能吃满全部工作线程。
 *
 * <p>监听线程提交任务后等待 Future 完成再 ack，ack 仍然晚于执行，保持 at-least-once。
 * 档位队列满 → {@link RejectedExecutionException}，调用方按原优先级延迟重投形成背压。
 *
 * <p>契约：docs/design-docs/0002-judge-pool.md §4.2。
 */
@Slf4j
public class WeightedJudgeDispatcher {

    /** 加权派发模式：4:2:1。 */
    private static final String[] PATTERN = { JudgeMqConstants.PRIORITY_HIGH, JudgeMqConstants.PRIORITY_HIGH,
            JudgeMqConstants.PRIORITY_HIGH, JudgeMqConstants.PRIORITY_HIGH, JudgeMqConstants.PRIORITY_NORMAL,
            JudgeMqConstants.PRIORITY_NORMAL, JudgeMqConstants.PRIORITY_RUN };

    private final Map<String, BlockingQueue<Runnable>> lanes = new HashMap<>(4);
    private final ThreadPoolExecutor workers;
    private final Thread dispatcher;
    private final AtomicInteger cursor = new AtomicInteger();
    private final Object signal = new Object();
    private volatile boolean running = true;
    private final AtomicInteger inFlight = new AtomicInteger();

    public WeightedJudgeDispatcher(JudgeAsyncProperties properties) {
        JudgeAsyncProperties.AsyncProps async = properties.getAsync();
        lanes.put(JudgeMqConstants.PRIORITY_HIGH, new ArrayBlockingQueue<>(async.getSubmitPoolQueue()));
        lanes.put(JudgeMqConstants.PRIORITY_NORMAL, new ArrayBlockingQueue<>(async.getSubmitPoolQueue()));
        lanes.put(JudgeMqConstants.PRIORITY_RUN, new ArrayBlockingQueue<>(async.getRunPoolQueue()));

        AtomicInteger sequence = new AtomicInteger();
        this.workers = new ThreadPoolExecutor(async.getSubmitPoolCore(), async.getSubmitPoolCore(), 0L,
                TimeUnit.MILLISECONDS, new SynchronousQueue<>(), runnable -> {
                    Thread thread = new Thread(runnable, "judge-worker-" + sequence.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());

        this.dispatcher = new Thread(this::dispatchLoop, "judge-dispatcher");
        this.dispatcher.setDaemon(true);
        this.dispatcher.start();
        log.info("evt=judge.dispatcher.start workers={} highQueue={} normalQueue={} runQueue={} weights=4:2:1",
                async.getSubmitPoolCore(), async.getSubmitPoolQueue(), async.getSubmitPoolQueue(),
                async.getRunPoolQueue());
    }

    /**
     * 提交到指定档位。队列满时抛 {@link RejectedExecutionException}（调用方按原优先级延迟重投）。
     *
     * <p>注意 {@link BlockingQueue#add} 满时抛的是 {@code IllegalStateException}，调用方只 catch
     * {@link RejectedExecutionException}，所以这里必须用 {@code offer} 显式转换异常类型。
     *
     * <p>另外，监听线程数（high 4 + normal 8 + run 1 = 13）小于档位队列容量（默认 256），
     * 且每个监听线程提交后阻塞在 {@code future.get()}，所以队列实际最多积压
     * {@code 13 - 8 = 5} 个任务，**正常情况下永远不会满**。队列容量是防御性上限，
     * 真正的背压点是监听并发度；这条分支只在监听并发被调大或队列被调小时才生效。
     */
    public FutureTask<Void> submit(String priority, Runnable task) {
        String lane = JudgeMqConstants.normalizePriority(priority);
        BlockingQueue<Runnable> queue = lanes.get(lane);
        FutureTask<Void> future = new FutureTask<>(task, null);
        if (!queue.offer(future)) {
            throw new RejectedExecutionException("judge lane queue is full: " + lane);
        }
        synchronized (signal) {
            signal.notify();
        }
        return future;
    }

    public int queued() {
        int total = 0;
        for (BlockingQueue<Runnable> queue : lanes.values()) {
            total += queue.size();
        }
        return total;
    }

    public int inFlight() {
        return inFlight.get();
    }

    private void dispatchLoop() {
        while (running) {
            synchronized (signal) {
                if (queued() == 0) {
                    try {
                        signal.wait(200L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
            Runnable task = nextTask();
            if (task == null) {
                continue;
            }
            boolean handedOff = false;
            while (running && !handedOff) {
                try {
                    workers.execute(() -> {
                        inFlight.incrementAndGet();
                        try {
                            task.run();
                        } finally {
                            inFlight.decrementAndGet();
                        }
                    });
                    handedOff = true;
                } catch (RejectedExecutionException e) {
                    // 所有工作线程都在忙：派发线程等一下再试，保证加权顺序不被 FIFO 队列打乱
                    LockSupport.parkNanos(1_000_000L);
                }
            }
        }
    }

    /**
     * 按加权模式取下一个任务；空档位顺延（工作 conserving）。
     */
    private Runnable nextTask() {
        int start = Math.floorMod(cursor.getAndIncrement(), PATTERN.length);
        for (int i = 0; i < PATTERN.length; i++) {
            BlockingQueue<Runnable> queue = lanes.get(PATTERN[(start + i) % PATTERN.length]);
            Runnable task = queue.poll();
            if (task != null) {
                return task;
            }
        }
        return null;
    }

    public void shutdown() {
        running = false;
        synchronized (signal) {
            signal.notifyAll();
        }
        dispatcher.interrupt();
        workers.shutdownNow();
    }
}
