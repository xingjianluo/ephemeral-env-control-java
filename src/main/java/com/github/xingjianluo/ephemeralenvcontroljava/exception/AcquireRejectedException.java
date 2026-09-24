package com.github.xingjianluo.ephemeralenvcontroljava.exception;

/**
 * 申请被拒绝（配额超额 / 环境被他人持有）。属于确定性结论，调用方不应原样重试。
 */
public class AcquireRejectedException extends RuntimeException {
    private final RejectionReason reason;

    public AcquireRejectedException(RejectionReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public RejectionReason getReason() {
        return reason;
    }
}
