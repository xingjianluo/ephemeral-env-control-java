package com.github.xingjianluo.ephemeralenvcontroljava.exception;

/**
 * 操作冲突（环境正处于回收中间态、幂等标识参数不一致等）。可重试。
 */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
