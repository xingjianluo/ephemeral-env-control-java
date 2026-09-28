package com.github.xingjianluo.ephemeralenvcontroljava.service;

import com.github.xingjianluo.ephemeralenvcontroljava.domain.AcquireResult;
import com.github.xingjianluo.ephemeralenvcontroljava.domain.ReclaimResult;
import com.github.xingjianluo.ephemeralenvcontroljava.domain.ReleaseResult;
import com.github.xingjianluo.ephemeralenvcontroljava.domain.RenewResult;

/**
 * 临时环境生命周期管控入口。
 */
public interface EphemeralEnvService {

    /**
     * 申请环境。
     * @param idempotencyId 调用方唯一幂等标识
     * @param owner 持有者身份
     * @param envName 目标环境名
     * @param ttlMillis 租约时长
     */
    AcquireResult acquire(String idempotencyId, String owner, String envName, long ttlMillis);

    /**
     * 持有者在租约到期前申请延长持有时间。
     * @param idempotencyId 调用方唯一幂等标识
     * @param owner 持有者身份（必须与当前租约持有者一致）
     * @param envName 目标环境名
     * @param extendMillis 在“当前到期时刻”基础上顺延的时长（不是从现在重新计时）
     */
    RenewResult renew(String idempotencyId, String owner, String envName, long extendMillis);

    /**
     * 持有者主动释放。
     */
    ReleaseResult release(String envName, String owner);

    /**
     * 过期 / 异常回收（幂等、可重试）。
     */
    ReclaimResult reclaim(String envName);

    /**
     * 扫描到期租约并回收，返回收敛结果。
     */
    java.util.List<ReclaimResult> sweepExpired();
}
