package com.github.xingjianluo.ephemeralenvcontroljava.exception;

/**
 * 续期被拒绝。原因可区分；是否可重试由 {@link RenewRejectionReason#isRetryable()} 决定。
 * 这是确定性结论（或明确的可重试冲突），不会出现“返回成功但环境不可用”。
 */
public class RenewRejectedException extends RuntimeException {
    private final RenewRejectionReason reason;

    public RenewRejectedException(RenewRejectionReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public RenewRejectionReason getReason() {
        return reason;
    }

    public boolean isRetryable() {
        return reason.isRetryable();
    }
}
