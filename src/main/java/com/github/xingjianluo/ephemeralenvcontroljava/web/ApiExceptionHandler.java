package com.github.xingjianluo.ephemeralenvcontroljava.web;

import com.github.xingjianluo.ephemeralenvcontroljava.service.IdempotentConflictException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/** 区分参数错误(400) 与幂等指纹冲突(409)，结论可解释。 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(IdempotentConflictException.class)
    public ResponseEntity<Map<String, Object>> handleConflict(IdempotentConflictException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("decision", "IDEMPOTENT_KEY_CONFLICT");
        body.put("idempotencyKey", e.getIdempotencyKey());
        body.put("reason", e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("decision", "INVALID_ARGUMENT");
        body.put("reason", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }
}
