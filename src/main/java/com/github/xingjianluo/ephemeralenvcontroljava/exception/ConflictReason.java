package com.github.xingjianluo.ephemeralenvcontroljava.exception;

/**
 * 状态冲突（{@link ConflictException}）的可区分原因。
 * 调用方据此判断应当“换幂等标识重试”还是“稍后用同一参数重试 / 修正请求”。
 */
public enum ConflictReason {

    /**
     * 幂等标识此前已被另一类操作（申请 ↔ 续期）使用。
     * 确定性冲突：重试无意义，调用方必须更换幂等标识。
     */
    IDEMPOTENCY_OPERATION_MISMATCH(false),

    /**
     * 同一幂等标识重复提交时绑定的 owner/envName 与首次不一致。
     * 确定性冲突：调用方应更换幂等标识（或改回首次参数）。
     */
    IDEMPOTENCY_REQUEST_MISMATCH(false),

    /**
     * 租约状态冲突（中间态、尚未到期、非持有者操作他人租约等）。
     * 与幂等标识无关：稍后重试或修正请求后可能得到不同结论。
     */
    LEASE_STATE_CONFLICT(true);

    private final boolean retryableWithSameRequest;

    ConflictReason(boolean retryableWithSameRequest) {
        this.retryableWithSameRequest = retryableWithSameRequest;
    }

    /** 是否可用同一请求稍后重试拿到不同结论。 */
    public boolean isRetryableWithSameRequest() {
        return retryableWithSameRequest;
    }
}
