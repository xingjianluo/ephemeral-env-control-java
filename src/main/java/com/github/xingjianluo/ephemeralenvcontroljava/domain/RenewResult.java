package com.github.xingjianluo.ephemeralenvcontroljava.domain;

/**
 * 续期结果。replayed=true 表示命中幂等记录，返回首次续期结论。
 * 续期只顺延到期时刻，不改变持有者与占用数量。
 */
public record RenewResult(
        Lease lease,
        boolean replayed,
        long previousExpireAtMillis,
        String reason
) {
}
