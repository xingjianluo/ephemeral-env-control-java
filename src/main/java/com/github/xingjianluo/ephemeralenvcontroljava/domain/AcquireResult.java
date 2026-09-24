package com.github.xingjianluo.ephemeralenvcontroljava.domain;

/**
 * 申请结果。replayed=true 表示命中幂等记录，返回首次结论。
 */
public record AcquireResult(
        Lease lease,
        boolean replayed,
        String reason
) {
}
