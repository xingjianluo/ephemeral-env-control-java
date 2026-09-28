package com.github.xingjianluo.ephemeralenvcontroljava.exception;

/**
 * 续期被拒绝的可区分原因。
 * - NOT_OWNER：调用方不是持有者，换身份无意义，不可原样重试；
 * - LEASE_NOT_FOUND：环境不存在租约；
 * - ALREADY_EXPIRED：租约已到期（含已懒标记 EXPIRED），续期迟到，禁止救活；
 * - RELEASING：环境正在主动释放途中；
 * - RECLAIMING：环境已进入回收（含回收失败待重试），续期迟到，禁止拉回可用；
 * - TERMINAL：环境已处于 RELEASED/RECLAIMED 终态。
 */
public enum RenewalRejectedReason {
    NOT_OWNER(false),
    LEASE_NOT_FOUND(false),
    ALREADY_EXPIRED(false),
    RELEASING(true),
    RECLAIMING(false),
    TERMINAL(false);

    private final boolean retryable;

    RenewalRejectedReason(boolean retryable) {
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
