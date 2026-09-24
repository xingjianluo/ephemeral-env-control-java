package com.github.xingjianluo.ephemeralenvcontroljava.service;

/** 同一幂等键被不同操作指纹复用时抛出，属于调用方冲突（HTTP 409）。 */
public class IdempotentConflictException extends RuntimeException {

    private final String idempotencyKey;

    public IdempotentConflictException(String idempotencyKey, String message) {
        super(message);
        this.idempotencyKey = idempotencyKey;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }
}
