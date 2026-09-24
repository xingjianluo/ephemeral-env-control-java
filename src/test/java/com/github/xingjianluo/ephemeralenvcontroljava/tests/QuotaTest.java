package com.github.xingjianluo.ephemeralenvcontroljava.tests;

import com.github.xingjianluo.ephemeralenvcontroljava.model.DecisionCode;
import com.github.xingjianluo.ephemeralenvcontroljava.model.LeaseResult;
import com.github.xingjianluo.ephemeralenvcontroljava.model.LeaseStatus;
import com.github.xingjianluo.ephemeralenvcontroljava.recycler.SimulatedRecycler;
import com.github.xingjianluo.ephemeralenvcontroljava.service.EnvLeaseManager;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 两级配额：持有者/全局超额可区分拒绝；释放与回收失败后配额守恒、不重复扣减。 */
class QuotaTest {

    private static final Logger log = LoggerFactory.getLogger(QuotaTest.class);

    private EnvLeaseManager newManager(MutableClock clock, SimulatedRecycler recycler) {
        return new EnvLeaseManager(clock, recycler, 2, 3, 1000L);
    }

    @Test
    void holderQuotaExceeded_isDistinguishableFromGlobal() {
        MutableClock clock = new MutableClock();
        SimulatedRecycler recycler = new SimulatedRecycler();
        EnvLeaseManager m = newManager(clock, recycler);

        m.apply("h-a1", "alice", "env-a1", null);
        m.apply("h-a2", "alice", "env-a2", null);
        LeaseResult rejected = m.apply("h-a3", "alice", "env-a3", null);
        log.info("[用例] op=APPLY idempotencyKey=h-a3 判定依据=持有者上限2已满 decision={} 全局已用={}",
                rejected.getCode(), m.quotaSnapshot().globalUsed());

        assertEquals(DecisionCode.HOLDER_QUOTA_EXCEEDED, rejected.getCode());
        assertEquals(2, m.quotaSnapshot().globalUsed());
        assertTrue(m.auditConservation());

        // 换一个持有者仍可申请（证明不是全局维度拒绝）
        LeaseResult bob = m.apply("h-b1", "bob", "env-b1", null);
        assertEquals(DecisionCode.APPLY_SUCCESS, bob.getCode());
    }

    @Test
    void globalQuotaExceeded() {
        MutableClock clock = new MutableClock();
        SimulatedRecycler recycler = new SimulatedRecycler();
        EnvLeaseManager m = newManager(clock, recycler);

        m.apply("g-a1", "alice", "env-a1", null);
        m.apply("g-a2", "alice", "env-a2", null);
        m.apply("g-b1", "bob", "env-b1", null);
        LeaseResult rejected = m.apply("g-b2", "bob", "env-b2", null);
        log.info("[用例] op=APPLY idempotencyKey=g-b2 判定依据=全局上限3已满 decision={}", rejected.getCode());

        assertEquals(DecisionCode.GLOBAL_QUOTA_EXCEEDED, rejected.getCode());
        assertEquals(3, m.quotaSnapshot().globalUsed());
        assertTrue(m.auditConservation());
    }

    @Test
    void releaseReturnsQuota_andCanReapply() {
        MutableClock clock = new MutableClock();
        SimulatedRecycler recycler = new SimulatedRecycler();
        EnvLeaseManager m = newManager(clock, recycler);

        m.apply("q1", "alice", "env-a1", null);
        m.apply("q2", "alice", "env-a2", null);
        assertEquals(DecisionCode.HOLDER_QUOTA_EXCEEDED,
                m.apply("q3", "alice", "env-a3", null).getCode());

        m.release("qrel", "alice", "env-a1");
        log.info("[用例] op=RELEASE idempotencyKey=qrel 判定依据=释放归还持有者配额 全局已用={}",
                m.quotaSnapshot().globalUsed());

        LeaseResult again = m.apply("q4", "alice", "env-a3", null);
        assertEquals(DecisionCode.APPLY_SUCCESS, again.getCode());
        assertTrue(m.auditConservation());
    }

    @Test
    void failedRecycle_keepsQuota_andRetrySucceedsOnce() {
        MutableClock clock = new MutableClock();
        SimulatedRecycler recycler = new SimulatedRecycler();
        EnvLeaseManager m = newManager(clock, recycler);

        m.apply("f0", "alice", "env-ok", null);
        m.apply("f1", "alice", "env-fail", null);
        recycler.failRecycling("env-fail");

        LeaseResult failed = m.release("frel1", "alice", "env-fail");
        log.info("[用例] op=RELEASE idempotencyKey=frel1 判定依据=回收器抛异常 decision={} 状态={} 全局已用={}",
                failed.getCode(), m.getLease("env-fail").getStatus(), m.quotaSnapshot().globalUsed());
        assertEquals(DecisionCode.RECYCLE_FAILED, failed.getCode());
        assertEquals(LeaseStatus.RELEASING, m.getLease("env-fail").getStatus());
        // 回收失败配额不得释放（否则泄漏给超额申请）
        assertEquals(2, m.quotaSnapshot().globalUsed());
        assertEquals(DecisionCode.HOLDER_QUOTA_EXCEEDED,
                m.apply("f2", "alice", "env-other", null).getCode());

        // 故障恢复后重试：成功，配额恰好归还一次
        recycler.recover("env-fail");
        LeaseResult retry = m.retryRecycle("fretry1", "env-fail");
        log.info("[用例] op=RETRY_RECYCLE idempotencyKey=fretry1 判定依据=故障恢复后重试 decision={} 全局已用={}",
                retry.getCode(), m.quotaSnapshot().globalUsed());
        assertEquals(DecisionCode.RECYCLE_SUCCESS, retry.getCode());
        assertEquals(LeaseStatus.RELEASED, m.getLease("env-fail").getStatus());
        assertEquals(1, m.quotaSnapshot().globalUsed());

        // 再次重试：已释放，拒绝且不得重复扣减（计数器不能变负）
        LeaseResult dup = m.retryRecycle("fretry2", "env-fail");
        log.info("[用例] op=RETRY_RECYCLE idempotencyKey=fretry2 判定依据=成功后再次回收 decision={}",
                dup.getCode());
        assertEquals(DecisionCode.ALREADY_RELEASED, dup.getCode());
        assertEquals(1, m.quotaSnapshot().globalUsed());
        assertTrue(m.auditConservation());
    }

    @Test
    void expiredLease_stillOccupiesQuotaUntilRecycled() {
        MutableClock clock = new MutableClock();
        SimulatedRecycler recycler = new SimulatedRecycler();
        EnvLeaseManager m = newManager(clock, recycler);

        m.apply("e1", "alice", "env-exp", 100L);
        m.apply("e2", "alice", "env-keep", null);
        clock.advanceMillis(101);
        m.sweepExpired();
        log.info("[用例] op=EXPIRE_SWEEP env=env-exp 判定依据=到期仅转EXPIRED不归还配额 状态={} 全局已用={}",
                m.getLease("env-exp").getStatus(), m.quotaSnapshot().globalUsed());

        assertEquals(LeaseStatus.EXPIRED, m.getLease("env-exp").getStatus());
        assertEquals(2, m.quotaSnapshot().globalUsed());
        assertEquals(DecisionCode.HOLDER_QUOTA_EXCEEDED,
                m.apply("e3", "alice", "env-x", null).getCode());

        LeaseResult rec = m.expireRecycle("erec", "env-exp");
        assertEquals(DecisionCode.RECYCLE_SUCCESS, rec.getCode());
        assertEquals(1, m.quotaSnapshot().globalUsed());
        assertTrue(m.auditConservation());
    }
}
