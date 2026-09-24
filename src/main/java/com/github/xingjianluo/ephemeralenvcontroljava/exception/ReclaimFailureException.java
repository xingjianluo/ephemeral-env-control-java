package com.github.xingjianluo.ephemeralenvcontroljava.exception;

/**
 * 回收中途失败。租约停留在中间态，允许且应当重试回收。
 */
public class ReclaimFailureException extends RuntimeException {
    public ReclaimFailureException(String message, Throwable cause) {
        super(message, cause);
    }
    public ReclaimFailureException(String message) {
        super(message);
    }
}
