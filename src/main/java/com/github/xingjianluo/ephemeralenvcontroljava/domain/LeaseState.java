package com.github.xingjianluo.ephemeralenvcontroljava.domain;

/**
 * 租约生命周期状态：
 * ACTIVE  -> 已申请成功且未到期；
 * EXPIRED -> 租约到期（检测态，等待回收）；
 * RELEASING / RECLAIMING -> 正常释放 / 异常或过期回收进行中；
 * RELEASED / RECLAIMED   -> 已完成释放 / 回收（终态）。
 */
public enum LeaseState {
    ACTIVE,
    EXPIRED,
    RELEASING,
    RECLAIMING,
    RELEASED,
    RECLAIMED
}
