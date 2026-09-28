package com.github.xingjianluo.ephemeralenvcontroljava.exception;

/**
 * 续期被拒绝的可区分原因。
 * retryable 标明该结论下调用方是否可以稍后用同一参数重试并期待不同结论。
 */
public enum RenewRejectionReason {
    /** 不是该环境的持有者。确定性结论，不应原样重试。 */
    NOT_OWNER(false),
    /** 租约已经到期（含到期懒标记后）。确定性结论，不能靠续期救活。 */
    ALREADY_EXPIRED(false),
    /** 环境已进入释放 / 回收终态。确定性结论。 */
    ALREADY_TERMINAL(false),
    /** 环境不存在任何租约。确定性结论。 */
    LEASE_NOT_FOUND(false),
    /** 环境正在释放 / 回收途中。结论未定，稍后重试可拿到确定结论。 */
    TRANSITION_IN_PROGRESS(true);

    private final boolean retryable;

    RenewRejectionReason(boolean retryable) {
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
