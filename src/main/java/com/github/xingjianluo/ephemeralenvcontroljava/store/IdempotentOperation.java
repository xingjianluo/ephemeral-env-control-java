package com.github.xingjianluo.ephemeralenvcontroljava.store;

/**
 * 幂等记录归属的操作类型：申请与续期各自独立命名空间，
 * 同一幂等标识不能跨操作复用。
 */
public enum IdempotentOperation {
    ACQUIRE,
    RENEW
}
