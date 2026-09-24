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
}
