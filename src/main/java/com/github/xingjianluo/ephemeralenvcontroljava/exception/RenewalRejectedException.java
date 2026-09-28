package com.github.xingjianluo.ephemeralenvcontroljava.exception;

/**
 * 续期被拒绝。携带可区分原因 {@link RenewalRejectedReason} 与是否可重试标志，
 * 调用方永远不会在续期失败时拿到“成功但环境不可用”的假结论。
 * 首次拒绝结论同样会被幂等记录，重复提交一致重放。
 */
public class RenewalRejectedException extends RuntimeException {

    private final RenewalRejectedReason reason;
    private final boolean retryable;

    public RenewalRejectedException(RenewalRejectedReason reason, String message) {
        super(message);
        this.reason = reason;
        this.retryable = reason.isRetryable();
    }

    public RenewalRejectedReason getReason() {
        return reason;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
