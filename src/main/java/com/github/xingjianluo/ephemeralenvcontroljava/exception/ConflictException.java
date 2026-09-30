package com.github.xingjianluo.ephemeralenvcontroljava.exception;

/**
 * 操作冲突（中间态、未到期、幂等标识跨操作复用 / 参数不一致等）。
 *
 * <p>具体语义见 {@link ConflictReason}：
 * <ul>
 *   <li>{@link ConflictReason#IDEMPOTENCY_OPERATION_MISMATCH} /
 *       {@link ConflictReason#IDEMPOTENCY_REQUEST_MISMATCH}：确定性冲突，
 *       同一请求原样重试无意义，调用方应更换幂等标识或修正参数；</li>
 *   <li>{@link ConflictReason#LEASE_STATE_CONFLICT}：租约状态冲突，
 *       与幂等标识无关，稍后用同一请求重试可能得到不同结论。</li>
 * </ul>
 */
public class ConflictException extends RuntimeException {

    private final ConflictReason reason;

    /** 租约状态冲突等通用构造。 */
    public ConflictException(String message) {
        this(ConflictReason.LEASE_STATE_CONFLICT, message);
    }

    public ConflictException(ConflictReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public ConflictReason getReason() {
        return reason;
    }
}
