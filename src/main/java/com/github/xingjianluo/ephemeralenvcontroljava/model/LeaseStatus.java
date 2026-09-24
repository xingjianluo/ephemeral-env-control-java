package com.github.xingjianluo.ephemeralenvcontroljava.model;

/**
 * 租约状态机：
 * <pre>
 *   ACTIVE ──到期──▶ EXPIRED ──回收成功──▶ RELEASED
 *     │                 │
 *     └──主动释放/回收──┴─回收失败(中间态)─▶ RELEASING ──重试成功──▶ RELEASED
 *                                              │
 *                                              └──重试失败──▶ EXPIRED(保持占用, 可再次重试)
 * </pre>
 */
public enum LeaseStatus {
    /** 已申请成功，租约持有中（占用持有者与全局配额） */
    ACTIVE,
    /** 租约已到期，等待回收（仍占用配额，直到回收成功） */
    EXPIRED,
    /** 回收中途失败后的中间态：仍占用配额，允许重试，不允许重复发起 */
    RELEASING,
    /** 回收成功的终态：配额已归还，可被同一环境重新申请（换代） */
    RELEASED
}
