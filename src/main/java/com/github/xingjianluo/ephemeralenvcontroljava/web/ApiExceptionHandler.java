package com.github.xingjianluo.ephemeralenvcontroljava.web;

import com.github.xingjianluo.ephemeralenvcontroljava.exception.AcquireRejectedException;
import com.github.xingjianluo.ephemeralenvcontroljava.exception.ConflictException;
import com.github.xingjianluo.ephemeralenvcontroljava.exception.ReclaimFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 三类失败的 HTTP 语义刻意区分：
 * - 409 + rejected：确定性拒绝（配额 / 被他人占用），不应原样重试；
 * - 409 + conflict：状态冲突（中间态 / 参数与幂等键不一致），可重试或修正；
 * - 202 + retryable：回收中途失败，租约停留在 RECLAIMING，应重试回收。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(AcquireRejectedException.class)
    public ResponseEntity<Map<String, Object>> handleRejected(AcquireRejectedException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "type", "rejected",
                "reason", ex.getReason().name(),
                "retryable", false,
                "message", ex.getMessage()));
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<Map<String, Object>> handleConflict(ConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "type", "conflict",
                "retryable", true,
                "message", ex.getMessage()));
    }

    @ExceptionHandler(ReclaimFailureException.class)
    public ResponseEntity<Map<String, Object>> handleReclaimFailure(ReclaimFailureException ex) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of(
                "type", "reclaim_failed",
                "retryable", true,
                "state", "RECLAIMING",
                "message", ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "type", "bad_request",
                "retryable", false,
                "message", ex.getMessage()));
    }
}
