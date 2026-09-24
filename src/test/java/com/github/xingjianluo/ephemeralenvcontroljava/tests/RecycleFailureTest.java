package com.github.xingjianluo.ephemeralenvcontroljava.tests;

import com.github.xingjianluo.ephemeralenvcontroljava.model.DecisionCode;
import com.github.xingjianluo.ephemeralenvcontroljava.model.LeaseResult;
import com.github.xingjianluo.ephemeralenvcontroljava.model.LeaseStatus;
import com.github.xingjianluo.ephemeralenvcontroljava.model.OperationType;
import com.github.xingjianluo.ephemeralenvcontroljava.recycler.SimulatedRecycler;
import com.github.xingjianluo.ephemeralenvcontroljava.service.EnvLeaseManager;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 过期链路、重复/迟到/并发回收的确定收敛与可重试性。 */
class RecycleFailureTest {

    private static final Logger log = LoggerFactory.getLogger(RecycleFailureTest.class);

    private EnvLeaseManager newManager(MutableClock clock, SimulatedRecycler recycler) {
        return new EnvLeaseManager(clock, recycler, 3, 5, 1000L);
    }

    @Test
    void expireThenRecycle_isDeterministic() {
        MutableClock clock = new MutableClock();
        SimulatedRecycler recycler = new SimulatedRecycler();
        EnvLeaseManager m = newManager(clock, recycler);

        m.apply("x1", "alice", "env-x", 100L);
        clock.advanceMillis(100); // 恰好到期
        List<String> swept = m.sweepExpired();
        log.info("[用例] op=EXPIRE_SWEEP idempotencyKey=- env=env-x 判定依据=now>=expireAt swept={}", swept);
        assertEquals(List.of("env-x"), swept);
        assertEquals(LeaseStatus.EXPIRED, m.getLease("env-x").getStatus());

        // 重复 sweep 幂等：不再返回
        assertTrue(m.sweepExpired().isEmpty());

        LeaseResult rec = m.expireRecycle("xrec", "env-x");
        log.info("[用例] op=EXPIRE_RECYCLE idempotencyKey=xrec 判定依据=EXPIRED->RELEASING->RELEASED decision={}",
                rec.getCode());
        assertEquals(DecisionCode.RECYCLE_SUCCESS, rec.getCode());
        assertEquals(LeaseStatus.RELEASED, m.getLease("env-x").getStatus());
        assertTrue(m.auditConservation());
    }

    @Test
    void duplicateRecycle_sameKey_replaysFrozenResult() {
        MutableClock clock = new MutableClock();
        SimulatedRecycler recycler = new SimulatedRecycler();
        EnvLeaseManager m = newManager(clock, recycler);

        m.apply("d1", "alice", "env-d", 100L);
        clock.advanceMillis(200);
        LeaseResult first = m.expireRecycle("drec", "env-d");
        LeaseResult second = m.expireRecycle("drec", "env-d");
        log.info("[用例] op=EXPIRE_RECYCLE idempotencyKey=drec 判定依据=同键重复回收 decision1={} decision2={}",
                first.getCode(), second.getCode());

        assertEquals(DecisionCode.RECYCLE_SUCCESS, first.getCode());
        assertEquals(first.getCode(), second.getCode());
        assertEquals(first.getGenerationAtDecision(), second.getGenerationAtDecision());
        assertEquals(0, m.quotaSnapshot().globalUsed());
        assertTrue(m.auditConservation());
    }

    @Test
    void duplicateRecycle_newKey_afterRelease_isRejectedWithoutRollback() {
        MutableClock clock = new MutableClock();
        SimulatedRecycler recycler = new SimulatedRecycler();
        EnvLeaseManager m = newManager(clock, recycler);

        m.apply("d2", "alice", "env-d2", null);
        m.release("drel", "alice", "env-d2");
        LeaseResult late = m.expireRecycle("drec2", "env-d2");
        log.info("[用例] op=EXPIRE_RECYCLE idempotencyKey=drec2 判定依据=已RELEASED后新键回收 decision={} 状态={}",
                late.getCode(), m.getLease("env-d2").getStatus());

        assertEquals(DecisionCode.ALREADY_RELEASED, late.getCode());
        assertEquals(LeaseStatus.RELEASED, m.getLease("env-d2").getStatus());
        assertTrue(late.getCode().isConflict());
        assertFalse(late.getCode().isRetryable());
        assertTrue(m.auditConservation());
    }

    @Test
    void lateRecycleFromOldGeneration_isIgnoredAndStateKept() {
        MutableClock clock = new MutableClock();
        SimulatedRecycler recycler = new SimulatedRecycler();
        EnvLeaseManager m = newManager(clock, recycler);

        // 第一代：申请并释放成功（gen 0 -> 1）
        m.apply("l1", "alice", "env-late", null);
        m.release("lrel", "alice", "env-late");
        long genAfterRelease = m.getLease("env-late").getGeneration();

        // 换代重开：第二代 ACTIVE
        LeaseResult reopened = m.apply("l2", "bob", "env-late", null);
        log.info("[用例] op=APPLY idempotencyKey=l2 判定依据=换代重开 decision={} 当前代={}",
                reopened.getCode(), m.getLease("env-late").getGeneration());

        // 迟到的旧代(gen=0)回收回调抵达：必须被忽略，不能把第二代 ACTIVE 打回 RELEASED
        LeaseResult late = m.completeRecycle("late-cb", null, "env-late",
                genAfterRelease - 1, OperationType.EXPIRE_RECYCLE, true);
        log.info("[用例] op=LATE_CALLBACK idempotencyKey=late-cb 判定依据=旧代回调晚于换代 decision={} 当前状态={}",
                late.getCode(), m.getLease("env-late").getStatus());

        assertEquals(DecisionCode.LATE_RECYCLE_IGNORED, late.getCode());
        assertEquals(LeaseStatus.ACTIVE, m.getLease("env-late").getStatus());
        assertEquals("bob", m.getLease("env-late").getHolder());
        assertEquals(1, m.quotaSnapshot().globalUsed());
        assertTrue(m.auditConservation());
    }

    @Test
    void failedThenRetriedRecycle_convergesAndIsRetryable() {
        MutableClock clock = new MutableClock();
        SimulatedRecycler recycler = new SimulatedRecycler();
        EnvLeaseManager m = newManager(clock, recycler);

        m.apply("r1", "alice", "env-r", null);
        recycler.failRecycling("env-r");
        LeaseResult failed = m.release("rrel", "alice", "env-r");
        log.info("[用例] op=RELEASE idempotencyKey=rrel 判定依据=首次回收失败 decision={} retryable={}",
                failed.getCode(), failed.getCode().isRetryable());
        assertEquals(DecisionCode.RECYCLE_FAILED, failed.getCode());
        assertTrue(failed.getCode().isRetryable());
        assertFalse(failed.getCode().isConflict());
        assertEquals(LeaseStatus.RELEASING, m.getLease("env-r").getStatus());

        // 失败期间再次失败：仍收敛 RELEASING，配额不重复扣减
        LeaseResult failedAgain = m.retryRecycle("rretry-0", "env-r");
        assertEquals(DecisionCode.RECYCLE_FAILED, failedAgain.getCode());

        recycler.recover("env-r");
        LeaseResult ok = m.retryRecycle("rretry-1", "env-r");
        log.info("[用例] op=RETRY_RECYCLE idempotencyKey=rretry-1 判定依据=故障恢复后重试成功 decision={}",
                ok.getCode());
        assertEquals(DecisionCode.RECYCLE_SUCCESS, ok.getCode());
        assertEquals(LeaseStatus.RELEASED, m.getLease("env-r").getStatus());
        assertEquals(0, m.quotaSnapshot().globalUsed());
        assertTrue(m.auditConservation());
    }

    @Test
    void nonOwnerRelease_isRejected() {
        MutableClock clock = new MutableClock();
        SimulatedRecycler recycler = new SimulatedRecycler();
        EnvLeaseManager m = newManager(clock, recycler);

        m.apply("o1", "alice", "env-o", null);
        LeaseResult denied = m.release("orel", "bob", "env-o");
        log.info("[用例] op=RELEASE idempotencyKey=orel 判定依据=非持有者释放 decision={}", denied.getCode());
        assertEquals(DecisionCode.NOT_LEASE_OWNER, denied.getCode());
        assertEquals(LeaseStatus.ACTIVE, m.getLease("env-o").getStatus());
        assertEquals(1, m.quotaSnapshot().globalUsed());
        assertTrue(m.auditConservation());
    }

    @Test
    void recycleUnknownLease_isRejected() {
        MutableClock clock = new MutableClock();
        SimulatedRecycler recycler = new SimulatedRecycler();
        EnvLeaseManager m = newManager(clock, recycler);

        LeaseResult r = m.expireRecycle("none", "ghost");
        log.info("[用例] op=EXPIRE_RECYCLE idempotencyKey=none 判定依据=租约不存在 decision={}", r.getCode());
        assertEquals(DecisionCode.LEASE_NOT_FOUND, r.getCode());
    }
}
