package com.github.xingjianluo.ephemeralenvcontroljava.tests;

import com.github.xingjianluo.ephemeralenvcontroljava.model.DecisionCode;
import com.github.xingjianluo.ephemeralenvcontroljava.model.Lease;
import com.github.xingjianluo.ephemeralenvcontroljava.model.LeaseResult;
import com.github.xingjianluo.ephemeralenvcontroljava.model.LeaseStatus;
import com.github.xingjianluo.ephemeralenvcontroljava.recycler.SimulatedRecycler;
import com.github.xingjianluo.ephemeralenvcontroljava.service.EnvLeaseManager;
import com.github.xingjianluo.ephemeralenvcontroljava.service.IdempotentConflictException;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 生命周期主路径 + 幂等“只生效一次、结论一致”验证。 */
class LifecycleIdempotencyTest {

    private static final Logger log = LoggerFactory.getLogger(LifecycleIdempotencyTest.class);

    private EnvLeaseManager newManager(MutableClock clock, SimulatedRecycler recycler) {
        // 持有者上限 3，全局上限 5，默认 TTL 1000ms
        return new EnvLeaseManager(clock, recycler, 3, 5, 1000L);
    }

    @Test
    void apply_release_happyPath() {
        MutableClock clock = new MutableClock();
        SimulatedRecycler recycler = new SimulatedRecycler();
        EnvLeaseManager m = newManager(clock, recycler);

        LeaseResult a = m.apply("key-apply-1", "alice", "env-1", null);
        log.info("[用例] op=APPLY idempotencyKey=key-apply-1 判定依据=正常申请 decision={}", a.getCode());
        assertEquals(DecisionCode.APPLY_SUCCESS, a.getCode());
        assertEquals(LeaseStatus.ACTIVE, m.getLease("env-1").getStatus());
        assertEquals(1, m.quotaSnapshot().globalUsed());
        assertTrue(m.auditConservation());

        LeaseResult r = m.release("key-release-1", "alice", "env-1");
        log.info("[用例] op=RELEASE idempotencyKey=key-release-1 判定依据=未到期主动释放 decision={}", r.getCode());
        assertEquals(DecisionCode.RELEASE_SUCCESS, r.getCode());
        assertEquals(LeaseStatus.RELEASED, m.getLease("env-1").getStatus());
        assertNull(m.getLease("env-1").getHolder());
        assertEquals(0, m.quotaSnapshot().globalUsed());
        assertTrue(m.auditConservation());
    }

    @Test
    void duplicateApply_sameIdempotencyKey_appliesOnceAndReturnsSameResult() {
        MutableClock clock = new MutableClock();
        SimulatedRecycler recycler = new SimulatedRecycler();
        EnvLeaseManager m = newManager(clock, recycler);

        LeaseResult first = m.apply("dup-key", "alice", "env-2", 1000L);
        LeaseResult second = m.apply("dup-key", "alice", "env-2", 1000L);
        LeaseResult third = m.apply("dup-key", "alice", "env-2", 9999L);
        log.info("[用例] op=APPLY idempotencyKey=dup-key 判定依据=同键重复提交三次 decision1={} decision2={} decision3={}",
                first.getCode(), second.getCode(), third.getCode());

        assertEquals(DecisionCode.APPLY_SUCCESS, first.getCode());
        // 同键必须返回与首次完全一致的冻结结论（包括首次的 TTL/到期时间），且只生效一次
        assertEquals(first.getCode(), second.getCode());
        assertEquals(first.getLeaseSnapshot().getExpireAt(), second.getLeaseSnapshot().getExpireAt());
        assertEquals(first.getLeaseSnapshot().getExpireAt(), third.getLeaseSnapshot().getExpireAt());
        assertEquals(1, m.quotaSnapshot().globalUsed());
        assertTrue(m.auditConservation());
    }

    @Test
    void sameIdempotencyKeyWithDifferentFingerprint_conflicts() {
        MutableClock clock = new MutableClock();
        SimulatedRecycler recycler = new SimulatedRecycler();
        EnvLeaseManager m = newManager(clock, recycler);

        m.apply("fingerprint-key", "alice", "env-3", null);
        // 同键换持有者：必须冲突拒绝，不能覆盖首次结论
        IdempotentConflictException ex = assertThrows(IdempotentConflictException.class,
                () -> m.apply("fingerprint-key", "bob", "env-3", null));
        log.info("[用例] op=APPLY idempotencyKey=fingerprint-key 判定依据=同键不同持有者 decision={} msg={}",
                DecisionCode.IDEMPOTENT_KEY_CONFLICT, ex.getMessage());

        assertEquals("alice", m.getLease("env-3").getHolder());
        assertEquals(1, m.quotaSnapshot().globalUsed());
        assertTrue(m.auditConservation());
    }

    @Test
    void differentHoldersContendSameEnv_noOverwrite() {
        MutableClock clock = new MutableClock();
        SimulatedRecycler recycler = new SimulatedRecycler();
        EnvLeaseManager m = newManager(clock, recycler);

        LeaseResult a = m.apply("contend-a", "alice", "env-4", null);
        LeaseResult b = m.apply("contend-b", "bob", "env-4", null);
        log.info("[用例] op=APPLY idempotencyKey=contend-a/contend-b 判定依据=不同持有者争用同一环境 decisionA={} decisionB={}",
                a.getCode(), b.getCode());

        assertEquals(DecisionCode.APPLY_SUCCESS, a.getCode());
        assertEquals(DecisionCode.ENV_OCCUPIED_BY_OTHER, b.getCode());
        assertEquals("alice", m.getLease("env-4").getHolder());
        // 被拒绝的争用不占用 bob 的配额，也不增加全局计数
        assertEquals(1, m.quotaSnapshot().globalUsed());
        assertEquals(1, m.quotaSnapshot().usedByHolder().get("alice"));
        assertNull(m.quotaSnapshot().usedByHolder().get("bob"));
        assertTrue(m.auditConservation());
    }

    @Test
    void selfRepeatApply_isRejectedAndNotDoubleCharged() {
        MutableClock clock = new MutableClock();
        SimulatedRecycler recycler = new SimulatedRecycler();
        EnvLeaseManager m = newManager(clock, recycler);

        m.apply("self-1", "alice", "env-5", null);
        LeaseResult again = m.apply("self-2", "alice", "env-5", null);
        log.info("[用例] op=APPLY idempotencyKey=self-2 判定依据=本人重复申请已持有环境 decision={}", again.getCode());

        assertEquals(DecisionCode.ENV_OCCUPIED_BY_SELF, again.getCode());
        assertEquals(1, m.quotaSnapshot().globalUsed());
        assertTrue(m.auditConservation());
    }

    @Test
    void releasedEnvCanBeReappliedWithNewGeneration() {
        MutableClock clock = new MutableClock();
        SimulatedRecycler recycler = new SimulatedRecycler();
        EnvLeaseManager m = newManager(clock, recycler);

        m.apply("gen-1", "alice", "env-6", null);
        long g0 = m.getLease("env-6").getGeneration();
        m.release("gen-rel-1", "alice", "env-6");
        clock.advanceMillis(10);
        LeaseResult re = m.apply("gen-2", "bob", "env-6", null);
        Lease lease = m.getLease("env-6");
        log.info("[用例] op=APPLY idempotencyKey=gen-2 判定依据=回收后换代重开 decision={} 旧代={} 新代={}",
                re.getCode(), g0, lease.getGeneration());

        assertEquals(DecisionCode.APPLY_SUCCESS, re.getCode());
        assertEquals("bob", lease.getHolder());
        assertEquals(LeaseStatus.ACTIVE, lease.getStatus());
        assertNotEquals(g0, lease.getGeneration());
        assertTrue(m.auditConservation());
    }
}
