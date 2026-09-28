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

    private MvcResult renew(String idem, String owner, String env, long extend) throws Exception {
        return mvc.perform(post("/api/envs/" + env + "/renew")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "idempotencyId", idem, "owner", owner, "extendMillis", extend))))
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

    @Test
    void renewSuccessReplayAndReclaimingRejectionAreDistinguishable() throws Exception {
        acquire("ra", "alice", "rn", 60_000);

        // 首次续期 200，到期时刻顺延
        MvcResult r1 = renew("rr1", "alice", "rn", 30_000);
        assertEquals(200, r1.getResponse().getStatus());
        JsonNode b1 = json.readTree(r1.getResponse().getContentAsString());
        assertFalse(b1.get("replayed").asBoolean());
        long firstExpire = b1.get("lease").get("expireAtMillis").asLong();

        // 同幂等键重放 200，replayed=true，结论一致
        MvcResult r2 = renew("rr1", "alice", "rn", 30_000);
        assertEquals(200, r2.getResponse().getStatus());
        JsonNode b2 = json.readTree(r2.getResponse().getContentAsString());
        assertTrue(b2.get("replayed").asBoolean());
        assertEquals(firstExpire, b2.get("lease").get("expireAtMillis").asLong());

        // 越权续期：409 renew_rejected / NOT_OWNER / retryable=false
        MvcResult denied = renew("rr2", "bob", "rn", 10_000);
        assertEquals(409, denied.getResponse().getStatus());
        JsonNode db = json.readTree(denied.getResponse().getContentAsString());
        assertEquals("renew_rejected", db.get("type").asText());
        assertEquals("NOT_OWNER", db.get("reason").asText());
        assertFalse(db.get("retryable").asBoolean());

        // 到期后回收失败一次（停留 RECLAIMING），续期返回 409 renew_rejected/
        // TRANSITION_IN_PROGRESS/retryable=true，绝不是 200 假成功
        now.addAndGet(90_001); // 越过续期后的到期点（1_000_000+60_000+30_000=1_090_000）
        simulator.failFirstAttempts("rn", 1);
        assertEquals(202, mvc.perform(post("/api/envs/rn/reclaim")).andReturn().getResponse().getStatus());
        MvcResult during = renew("rr3", "alice", "rn", 60_000);
        assertEquals(409, during.getResponse().getStatus());
        JsonNode dg = json.readTree(during.getResponse().getContentAsString());
        assertEquals("renew_rejected", dg.get("type").asText());
        assertEquals("TRANSITION_IN_PROGRESS", dg.get("reason").asText());
        assertTrue(dg.get("retryable").asBoolean());

        // 回收收敛后，同键得到 ALREADY_TERMINAL 确定性拒绝
        assertEquals(200, mvc.perform(post("/api/envs/rn/reclaim")).andReturn().getResponse().getStatus());
        MvcResult after = renew("rr3", "alice", "rn", 60_000);
        assertEquals(409, after.getResponse().getStatus());
        JsonNode ag = json.readTree(after.getResponse().getContentAsString());
        assertEquals("ALREADY_TERMINAL", ag.get("reason").asText());
        assertFalse(ag.get("retryable").asBoolean());
        testLog.info("[ASSERT] op=HTTP_RENEW basis=200-replay/409-not-owner/409-reclaiming/409-terminal");
    }
}
