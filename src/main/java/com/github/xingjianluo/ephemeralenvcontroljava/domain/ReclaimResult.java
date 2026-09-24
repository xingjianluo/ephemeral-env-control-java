package com.github.xingjianluo.ephemeralenvcontroljava.domain;

/**
 * 回收结果（过期回收 / 异常回收）。
 */
public record ReclaimResult(
        String envName,
        LeaseState finalState,
        boolean replayed,
        String reason
) {
}
