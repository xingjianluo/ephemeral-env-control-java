package com.github.xingjianluo.ephemeralenvcontroljava.recycler;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 本地模拟回收器：默认成功；可注入失败以验证“回收中途失败”的异常链路。
 * 全程纯内存，不依赖任何外部服务或凭据。
 */
public class SimulatedRecycler implements EnvRecycler {

    /** 当前处于“回收必失败”状态的 envId 集合。 */
    private final Set<String> failingEnvIds = Collections.synchronizedSet(new HashSet<>());

    /** 让指定环境后续回收抛出异常（模拟中途失败）。 */
    public void failRecycling(String envId) {
        failingEnvIds.add(envId);
    }

    /** 恢复指定环境的回收（模拟故障恢复，此后重试可成功）。 */
    public void recover(String envId) {
        failingEnvIds.remove(envId);
    }

    @Override
    public void recycle(String envId, long generation) throws Exception {
        if (failingEnvIds.contains(envId)) {
            throw new IllegalStateException("simulated recycle failure for env=" + envId
                    + " generation=" + generation);
        }
    }
}
