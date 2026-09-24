package com.github.xingjianluo.ephemeralenvcontroljava.store;

import com.github.xingjianluo.ephemeralenvcontroljava.domain.Lease;

/**
 * 幂等记录：保存某幂等标识首次申请的确定性结论。
 */
public record IdempotencyRecord(
        String idempotencyId,
        String owner,
        String envName,
        boolean accepted,
        Lease lease,
        String rejectionCode,
        String rejectionMessage,
        long createdAtMillis
) {
}
