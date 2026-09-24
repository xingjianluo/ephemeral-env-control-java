package com.github.xingjianluo.ephemeralenvcontroljava.reclaim;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 默认模拟器：按环境记录回收尝试次数，可配置前 N 次回收“中途失败”。
 * 仅使用本地状态，不依赖任何外部服务。
 */
@Component
public class CountingFailureSimulator implements ReclaimFailureSimulator {

    private final ConcurrentHashMap<String, AtomicInteger> failUntilAttempt = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> attemptCounts = new ConcurrentHashMap<>();

    /** 配置 envName 的前 failTimes 次回收尝试失败（用于测试异常回收链路）。 */
    public void failFirstAttempts(String envName, int failTimes) {
        failUntilAttempt.put(envName, new AtomicInteger(failTimes));
    }

    public int getAttemptCount(String envName) {
        AtomicInteger c = attemptCounts.get(envName);
        return c == null ? 0 : c.get();
    }

    public void reset() {
        failUntilAttempt.clear();
        attemptCounts.clear();
    }

    @Override
    public boolean attemptReclaim(String envName) {
        int attempt = attemptCounts.computeIfAbsent(envName, k -> new AtomicInteger()).incrementAndGet();
        AtomicInteger remaining = failUntilAttempt.get(envName);
        if (remaining != null && remaining.getAndDecrement() > 0) {
            throw new RecoverableReclaimFault("模拟回收中途失败: env=" + envName + ", attempt=" + attempt);
        }
        return true;
    }

    /** 模拟“可恢复”的外部回收故障：调用方捕获后应保留中间态并允许重试。 */
    public static class RecoverableReclaimFault extends RuntimeException {
        public RecoverableReclaimFault(String message) {
            super(message);
        }
    }
}
