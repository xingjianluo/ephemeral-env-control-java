package com.github.xingjianluo.ephemeralenvcontroljava.domain;

/**
 * 释放结果。
 */
public record ReleaseResult(
        String envName,
        LeaseState finalState,
        boolean replayed,
        String reason
) {
}
