package com.github.xingjianluo.ephemeralenvcontroljava.tests;

import com.github.xingjianluo.ephemeralenvcontroljava.recycler.EnvRecycler;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 可阻塞回收器：第一次 recycle 停在 gate 处，等待测试放行，制造“回收在途”窗口；
 * 统计真实回收调用次数，验证回收不被重复执行。
 */
public class BlockingRecycler implements EnvRecycler {

    private final CountDownLatch entered = new CountDownLatch(1);
    private final CountDownLatch gate = new CountDownLatch(1);
    private final AtomicInteger invocations = new AtomicInteger(0);
    private volatile String lastEnv;
    private volatile long lastGeneration;

    @Override
    public void recycle(String envId, long generation) {
        invocations.incrementAndGet();
        lastEnv = envId;
        lastGeneration = generation;
        entered.countDown();
        try {
            gate.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while blocked in recycle", e);
        }
    }

    /** 等待第一次回收进入阻塞点。 */
    public void awaitEntered() throws InterruptedException {
        entered.await();
    }

    /** 放行被阻塞的回收。 */
    public void releaseGate() {
        gate.countDown();
    }

    public int invocationCount() {
        return invocations.get();
    }

    public String getLastEnv() {
        return lastEnv;
    }

    public long getLastGeneration() {
        return lastGeneration;
    }
}
