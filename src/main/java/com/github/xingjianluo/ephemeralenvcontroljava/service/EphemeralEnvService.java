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
     * 持有者主动释放。
     */
    ReleaseResult release(String envName, String owner);

    /**
     * 持有者在租约到期前申请延长持有时间。
     * <p>规则：
     * <ul>
     *   <li>必须携带幂等标识与持有者身份；同一标识重复提交只生效一次并一致重放；</li>
     *   <li>仅 ACTIVE 且未到期、且 owner 匹配的租约可续期，到期时刻按原到期时刻顺延；</li>
     *   <li>非持有者、已过期、释放/回收途中或终态的环境均给出可区分、可重试的明确结论；</li>
     *   <li>续期不改变占用数量，不多占配额。</li>
     * </ul>
     * @param extendMillis 在当前到期时刻基础上顺延的时长（必须为正数）
     */
    RenewResult renew(String idempotencyId, String owner, String envName, long extendMillis);

    /**
     * 过期 / 异常回收（幂等、可重试）。
     */
    ReclaimResult reclaim(String envName);

    /**
     * 扫描到期租约并回收，返回收敛结果。
     */
    java.util.List<ReclaimResult> sweepExpired();
}
