package com.github.xingjianluo.ephemeralenvcontroljava.store;

import com.github.xingjianluo.ephemeralenvcontroljava.domain.Lease;

/**
 * 续期幂等记录：保存某幂等标识首次续期请求的确定性结论（成功或拒绝）。
 * 成功时 lease 为续期后的租约快照（用于一致重放，且保留续期次数与到期时刻）。
 */
public record RenewalIdempotencyRecord(
        String idempotencyId,
        String owner,
        String envName,
        boolean accepted,
        Lease lease,
        String rejectionCode,
        String rejectionMessage,
        boolean retryable,
        long createdAtMillis
) {
}
