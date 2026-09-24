package com.github.xingjianluo.ephemeralenvcontroljava.web;

import com.github.xingjianluo.ephemeralenvcontroljava.domain.AcquireResult;
import com.github.xingjianluo.ephemeralenvcontroljava.domain.ReclaimResult;
import com.github.xingjianluo.ephemeralenvcontroljava.domain.ReleaseResult;
import com.github.xingjianluo.ephemeralenvcontroljava.service.EphemeralEnvService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/envs")
public class EnvController {

    private final EphemeralEnvService service;

    public EnvController(EphemeralEnvService service) {
        this.service = service;
    }

    public record AcquireRequest(String idempotencyId, String owner, String envName, Long ttlMillis) { }
    public record ReleaseRequest(String owner) { }

    @PostMapping("/acquire")
    public AcquireResult acquire(@RequestBody AcquireRequest req) {
        return service.acquire(req.idempotencyId(), req.owner(), req.envName(),
                req.ttlMillis() == null ? 0L : req.ttlMillis());
    }

    @PostMapping("/{envName}/release")
    public ReleaseResult release(@PathVariable String envName, @RequestBody ReleaseRequest req) {
        return service.release(envName, req.owner());
    }

    @PostMapping("/{envName}/reclaim")
    public ReclaimResult reclaim(@PathVariable String envName) {
        return service.reclaim(envName);
    }

    @PostMapping("/sweep")
    public List<ReclaimResult> sweep() {
        return service.sweepExpired();
    }
}
