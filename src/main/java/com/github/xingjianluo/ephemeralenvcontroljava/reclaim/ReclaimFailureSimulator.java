package com.github.xingjianluo.ephemeralenvcontroljava.reclaim;

/**
 * 模拟回收外部动作中途失败的注入点（本地实现，不依赖外部服务）。
 */
public interface ReclaimFailureSimulator {
    /**
     * 执行回收动作；返回 true 表示成功，false / 抛异常表示中途失败。
     */
    boolean attemptReclaim(String envName);
}
