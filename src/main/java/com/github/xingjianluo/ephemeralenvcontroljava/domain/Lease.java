package com.github.xingjianluo.ephemeralenvcontroljava.domain;

/**
 * 单条临时环境租约（本地状态）。
 */
public record Lease(
        String envName,
        String owner,
        String idempotencyId,
        long acquiredAtMillis,
        long expireAtMillis,
        LeaseState state
) {
    public Lease withState(LeaseState newState) {
        return new Lease(envName, owner, idempotencyId, acquiredAtMillis, expireAtMillis, newState);
    }

    /**
     * 续期：在当前到期时刻基础上顺延 extendMillis（多次续期累加），不改变已流逝时间与占用状态。
     */
    public Lease withExtendedExpiration(long extendMillis) {
        return new Lease(envName, owner, idempotencyId, acquiredAtMillis,
                expireAtMillis + extendMillis, state);
    }
}
