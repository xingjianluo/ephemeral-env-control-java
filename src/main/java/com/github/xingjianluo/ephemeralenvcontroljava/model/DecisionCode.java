package com.github.xingjianluo.ephemeralenvcontroljava.model;

/**
 * 统一决策码：每次操作都有确定、可解释、可区分的结论。
 * success 区分成功/拒绝；retryable 区分失败可重试；conflict 区分状态冲突。
 */
public enum DecisionCode {
    APPLY_SUCCESS(true, false, false, "申请成功，租约生效"),
    HOLDER_QUOTA_EXCEEDED(false, false, false, "拒绝：持有者配额已满（按持有者维度核算）"),
    GLOBAL_QUOTA_EXCEEDED(false, false, false, "拒绝：全局配额已满（按全局维度核算）"),
    ENV_OCCUPIED_BY_SELF(false, false, true, "拒绝：环境已被本人持有，不能重复申请"),
    ENV_OCCUPIED_BY_OTHER(false, false, true, "拒绝：环境正被其他持有者持有/回收中，争用不得相互覆盖"),
    IDEMPOTENT_KEY_CONFLICT(false, false, true, "拒绝：幂等标识已被不同的操作/持有者/环境使用"),
    LEASE_NOT_FOUND(false, false, false, "拒绝：租约不存在"),
    NOT_LEASE_OWNER(false, false, true, "拒绝：非持有者无权操作该环境"),
    RELEASE_SUCCESS(true, false, false, "释放成功，配额已归还"),
    ALREADY_RELEASING(false, true, true, "冲突：回收已在途，勿重复回收，可稍后用新幂等键重试确认"),
    ALREADY_RELEASED(false, false, true, "冲突：环境已回收完成，重复/迟到回收被幂等拒绝"),
    RECYCLE_SUCCESS(true, false, false, "回收成功，配额已归还"),
    RECYCLE_FAILED(false, true, false, "失败：回收中途失败，状态已收敛为RELEASING且配额保持占用，可安全重试"),
    LATE_RECYCLE_IGNORED(false, false, true, "冲突：迟到回收属于旧代际，已忽略，当前状态不回退");

    private final boolean success;
    private final boolean retryable;
    private final boolean conflict;
    private final String reason;

    DecisionCode(boolean success, boolean retryable, boolean conflict, String reason) {
        this.success = success;
        this.retryable = retryable;
        this.conflict = conflict;
        this.reason = reason;
    }

    public boolean isSuccess() { return success; }
    public boolean isRetryable() { return retryable; }
    public boolean isConflict() { return conflict; }
    public String getReason() { return reason; }
}
