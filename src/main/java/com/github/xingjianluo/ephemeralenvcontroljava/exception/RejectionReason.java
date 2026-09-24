package com.github.xingjianluo.ephemeralenvcontroljava.exception;

/**
 * 申请被拒绝的可区分原因。
 */
public enum RejectionReason {
    OWNER_QUOTA_EXCEEDED,
    GLOBAL_QUOTA_EXCEEDED,
    ENV_HELD_BY_ANOTHER_OWNER,
    ALREADY_TERMINAL
}
