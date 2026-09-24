package com.github.xingjianluo.ephemeralenvcontroljava.web;

import com.github.xingjianluo.ephemeralenvcontroljava.model.Lease;
import com.github.xingjianluo.ephemeralenvcontroljava.model.LeaseResult;
import com.github.xingjianluo.ephemeralenvcontroljava.service.EnvLeaseManager;
import com.github.xingjianluo.ephemeralenvcontroljava.support.QuotaSnapshot;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 临时环境生命周期 REST 入口。所有结论以 decision 表达，HTTP 层统一 200/409/400。 */
@RestController
@RequestMapping("/api/envs")
public class EnvController {

    private final EnvLeaseManager manager;

    public EnvController(EnvLeaseManager manager) {
        this.manager = manager;
    }

    @PostMapping("/apply")
    public Map<String, Object> apply(@RequestBody ApplyRequest req) {
        return toView(manager.apply(req.getIdempotencyKey(), req.getHolder(),
                req.getEnvId(), req.getTtlMillis()));
    }

    @PostMapping("/release")
    public Map<String, Object> release(@RequestBody OperationRequest req) {
        return toView(manager.release(req.getIdempotencyKey(), req.getHolder(), req.getEnvId()));
    }

    @PostMapping("/expire-recycle")
    public Map<String, Object> expireRecycle(@RequestBody OperationRequest req) {
        return toView(manager.expireRecycle(req.getIdempotencyKey(), req.getEnvId()));
    }

    @PostMapping("/retry-recycle")
    public Map<String, Object> retryRecycle(@RequestBody OperationRequest req) {
        return toView(manager.retryRecycle(req.getIdempotencyKey(), req.getEnvId()));
    }

    @PostMapping("/sweep")
    public Map<String, Object> sweep() {
        List<String> expired = manager.sweepExpired();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("swept", expired);
        body.put("count", expired.size());
        return body;
    }

    @GetMapping("/{envId}")
    public Map<String, Object> get(@PathVariable String envId) {
        Lease lease = manager.getLease(envId);
        Map<String, Object> body = new LinkedHashMap<>();
        if (lease == null) {
            body.put("found", false);
        } else {
            body.put("found", true);
            body.put("lease", leaseView(lease));
        }
        return body;
    }

    @GetMapping("/quota")
    public QuotaSnapshot quota() {
        return manager.quotaSnapshot();
    }

    private Map<String, Object> toView(LeaseResult r) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("idempotencyKey", r.getIdempotencyKey());
        body.put("operation", r.getOperation());
        body.put("decision", r.getCode());
        body.put("success", r.isSuccess());
        body.put("retryable", r.getCode().isRetryable());
        body.put("conflict", r.getCode().isConflict());
        body.put("reason", r.getCode().getReason());
        body.put("basis", r.getReasonDetail());
        body.put("generation", r.getGenerationAtDecision());
        if (r.getLeaseSnapshot() != null) {
            body.put("lease", leaseView(r.getLeaseSnapshot()));
        }
        return body;
    }

    private Map<String, Object> leaseView(Lease l) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("envId", l.getEnvId());
        m.put("holder", l.getHolder());
        m.put("status", l.getStatus());
        m.put("acquiredAt", l.getAcquiredAt());
        m.put("expireAt", l.getExpireAt());
        m.put("generation", l.getGeneration());
        return m;
    }
}
