package com.github.xingjianluo.ephemeralenvcontroljava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.xingjianluo.ephemeralenvcontroljava.quota.QuotaService;
import com.github.xingjianluo.ephemeralenvcontroljava.reclaim.CountingFailureSimulator;
import com.github.xingjianluo.ephemeralenvcontroljava.service.DefaultEphemeralEnvService;
import com.github.xingjianluo.ephemeralenvcontroljava.store.InMemoryLeaseStore;
import com.github.xingjianluo.ephemeralenvcontroljava.time.Clock;
import com.github.xingjianluo.ephemeralenvcontroljava.web.ApiExceptionHandler;
import com.github.xingjianluo.ephemeralenvcontroljava.web.EnvController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/**
 * Web 层语义测试：确定性拒绝 / 冲突 / 可重试回收失败三类错误可区分。
 */
class EnvControllerTest {

    private static final Logger testLog = LoggerFactory.getLogger("TEST");

    private final AtomicLong now = new AtomicLong(1_000_000L);
    private MockMvc mvc;
    private ObjectMapper json = new ObjectMapper();
    private InMemoryLeaseStore store;
    private CountingFailureSimulator simulator;
    private QuotaService quota;

    @BeforeEach
    void setUp() {
        store = new InMemoryLeaseStore();
        simulator = new CountingFailureSimulator();
        quota = new QuotaService(store, 2, 4);
        Clock clock = now::get;
        DefaultEphemeralEnvService service = new DefaultEphemeralEnvService(store, quota, clock, simulator);
        mvc = standaloneSetup(new EnvController(service))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private MvcResult acquire(String idem, String owner, String env, long ttl) throws Exception {
        return mvc.perform(post("/api/envs/acquire")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "idempotencyId", idem, "owner", owner, "envName", env, "ttlMillis", ttl))))
                .andReturn();
    }

    @Test
    void acquireThenIdempotentReplayReturnsSameLease() throws Exception {
        MvcResult r1 = acquire("idem-001", "alice", "e1", 60_000);
        assertEquals(200, r1.getResponse().getStatus());
        JsonNode body1 = json.readTree(r1.getResponse().getContentAsString());
        assertFalse(body1.get("replayed").asBoolean());

        MvcResult r2 = acquire("idem-001", "alice", "e1", 60_000);
        assertEquals(200, r2.getResponse().getStatus());
        JsonNode body2 = json.readTree(r2.getResponse().getContentAsString());
        assertTrue(body2.get("replayed").asBoolean());
        assertEquals(body1.get("lease").get("acquiredAtMillis").asLong(),
                body2.get("lease").get("acquiredAtMillis").asLong());
        testLog.info("[ASSERT] idempotencyId=idem-001 op=HTTP_ACQUIRE basis=replayed-same-lease");
    }

    @Test
    void renewSuccessReplayAndRejectionAreDistinguishable() throws Exception {
        // 先申请
        acquire("rn-1", "alice", "rn1", 60_000);

        // 续期成功 200
        MvcResult ok1 = mvc.perform(post("/api/envs/rn1/renew")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "idempotencyId", "renew-1", "owner", "alice", "extendMillis", 30_000))))
                .andExpect(status().isOk()).andReturn();
        JsonNode okBody = json.readTree(ok1.getResponse().getContentAsString());
        assertFalse(okBody.get("replayed").asBoolean());

        // 同标识重放 200 replayed=true，到期时刻一致
        MvcResult ok2 = mvc.perform(post("/api/envs/rn1/renew")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "idempotencyId", "renew-1", "owner", "alice", "extendMillis", 30_000))))
                .andExpect(status().isOk()).andReturn();
        JsonNode replayBody = json.readTree(ok2.getResponse().getContentAsString());
        assertTrue(replayBody.get("replayed").asBoolean());
        assertEquals(okBody.get("lease").get("expireAtMillis").asLong(),
                replayBody.get("lease").get("expireAtMillis").asLong());

        // 越权续期 -> 409 renewal_rejected/NOT_OWNER/retryable=false
        MvcResult denied = mvc.perform(post("/api/envs/rn1/renew")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "idempotencyId", "renew-2", "owner", "bob", "extendMillis", 30_000))))
                .andExpect(status().isConflict()).andReturn();
        JsonNode db = json.readTree(denied.getResponse().getContentAsString());
        assertEquals("renewal_rejected", db.get("type").asText());
        assertEquals("NOT_OWNER", db.get("reason").asText());
        assertFalse(db.get("retryable").asBoolean());

        // 到期后续期 -> 409 renewal_rejected/ALREADY_EXPIRED
        // 首次续期 30s 已把到期点从 +60s 顺延到 +90s，需越过新到期点
        now.addAndGet(90_001);
        MvcResult late = mvc.perform(post("/api/envs/rn1/renew")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "idempotencyId", "renew-3", "owner", "alice", "extendMillis", 30_000))))
                .andExpect(status().isConflict()).andReturn();
        JsonNode lb = json.readTree(late.getResponse().getContentAsString());
        assertEquals("ALREADY_EXPIRED", lb.get("reason").asText());
        assertFalse(lb.get("retryable").asBoolean());
        testLog.info("[ASSERT] op=HTTP_RENEW basis=ok-replay-notowner-expired");
    }

    @Test
    void quotaRejectionConflictAndRetryableFailureAreDistinguishable() throws Exception {
        // 持有者配额打满 -> 409 rejected/OWNER_QUOTA_EXCEEDED/retryable=false
        acquire("q1", "alice", "q1", 60_000);
        acquire("q2", "alice", "q2", 60_000);
        MvcResult rejected = acquire("q3", "alice", "q3", 60_000);
        assertEquals(409, rejected.getResponse().getStatus());
        JsonNode rej = json.readTree(rejected.getResponse().getContentAsString());
        assertEquals("rejected", rej.get("type").asText());
        assertEquals("OWNER_QUOTA_EXCEEDED", rej.get("reason").asText());
        assertFalse(rej.get("retryable").asBoolean());

        // 未到期回收 -> 409 conflict/retryable=true
        MvcResult conflict = mvc.perform(post("/api/envs/q1/reclaim")).andReturn();
        assertEquals(409, conflict.getResponse().getStatus());
        JsonNode cf = json.readTree(conflict.getResponse().getContentAsString());
        assertEquals("conflict", cf.get("type").asText());
        assertTrue(cf.get("retryable").asBoolean());

        // 到期后回收失败一次 -> 202 reclaim_failed/retryable=true/state=RECLAIMING，再回收成功
        now.addAndGet(60_001);
        simulator.failFirstAttempts("q1", 1);
        MvcResult failed = mvc.perform(post("/api/envs/q1/reclaim")).andReturn();
        assertEquals(202, failed.getResponse().getStatus());
        JsonNode ff = json.readTree(failed.getResponse().getContentAsString());
        assertEquals("reclaim_failed", ff.get("type").asText());
        assertTrue(ff.get("retryable").asBoolean());
        assertEquals("RECLAIMING", ff.get("state").asText());

        MvcResult recovered = mvc.perform(post("/api/envs/q1/reclaim"))
                .andExpect(status().isOk()).andReturn();
        JsonNode ok = json.readTree(recovered.getResponse().getContentAsString());
        assertEquals("RECLAIMED", ok.get("finalState").asText());
        testLog.info("[ASSERT] op=HTTP_RECLAIM basis=202-retryable-then-200-reclaimed");
    }
}
