package com.github.xingjianluo.ephemeralenvcontroljava.store;

import com.github.xingjianluo.ephemeralenvcontroljava.domain.Lease;

/**
 * 幂等记录：保存某幂等标识首次操作（申请 / 续期）的确定性结论。
 */
public record IdempotencyRecord(
        String idempotencyId,
        IdempotentOperation operation,
        String owner,
        String envName,
        boolean accepted,
        Lease lease,
        String rejectionCode,
        String rejectionMessage,
        long createdAtMillis,
        /** 续期成功时的续期前到期时刻；其它场景为 0。 */
        long previousExpireAtMillis
) {
    /** 申请操作的兼容构造器。 */
    public IdempotencyRecord(String idempotencyId, String owner, String envName,
                             boolean accepted, Lease lease,
                             String rejectionCode, String rejectionMessage,
                             long createdAtMillis) {
        this(idempotencyId, IdempotentOperation.ACQUIRE, owner, envName, accepted, lease,
                rejectionCode, rejectionMessage, createdAtMillis, 0L);
    }
}
