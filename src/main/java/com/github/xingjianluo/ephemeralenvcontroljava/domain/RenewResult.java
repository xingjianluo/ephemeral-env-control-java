package com.github.xingjianluo.ephemeralenvcontroljava.domain;

/**
 * 续期结果。replayed=true 表示命中同一幂等标识的首次结论。
 */
public record RenewResult(
        Lease lease,
        boolean replayed,
        String reason
) {
}
