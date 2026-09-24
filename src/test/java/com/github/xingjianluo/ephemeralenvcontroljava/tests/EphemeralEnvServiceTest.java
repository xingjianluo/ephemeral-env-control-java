package com.github.xingjianluo.ephemeralenvcontroljava.tests;

import com.github.xingjianluo.ephemeralenvcontroljava.domain.AcquireResult;
import com.github.xingjianluo.ephemeralenvcontroljava.domain.LeaseState;
import com.github.xingjianluo.ephemeralenvcontroljava.domain.ReclaimResult;
import com.github.xingjianluo.ephemeralenvcontroljava.domain.ReleaseResult;
import com.github.xingjianluo.ephemeralenvcontroljava.exception.AcquireRejectedException;
import com.github.xingjianluo.ephemeralenvcontroljava.exception.ConflictException;
import com.github.xingjianluo.ephemeralenvcontroljava.exception.ReclaimFailureException;
import com.github.xingjianluo.ephemeralenvcontroljava.exception.RejectionReason;
import com.github.xingjianluo.ephemeralenvcontroljava.quota.QuotaService;
import com.github.xingjianluo.ephemeralenvcontroljava.reclaim.CountingFailureSimulator;
import com.github.xingjianluo.ephemeralenvcontroljava.service.DefaultEphemeralEnvService;
import com.github.xingjianluo.ephemeralenvcontroljava.store.InMemoryLeaseStore;
import com.github.xingjianluo.ephemeralenvcontroljava.time.Clock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 生命周期管控服务的单测。所有用例仅依赖本地状态与可控时钟。
 * 日志统一打印：幂等标识 / 操作类型 / 判定依据（见服务实现中的 op=... 日志）。
 */
class EphemeralEnvServiceTest {

    private static final Logger testLog = LoggerFactory.getLogger("TEST");

    static final class MutableClock implements Clock {
        private final AtomicLong t = new AtomicLong(1_000_000L);
        @Override public long nowMillis() { return t.get(); }
        void advance(long delta) { t.addAndGet(delta); }
    }

    private InMemoryLeaseStore store;
    private QuotaService quota;
    private MutableClock clock;
    private CountingFailureSimulator simulator;
    private DefaultEphemeralEnvService service;

    @BeforeEach
    void setUp() {
        store = new InMemoryLeaseStore();
        clock = new MutableClock();
        simulator = new CountingFailureSimulator();
        quota = new QuotaService(store, 2, 4);
        service = new DefaultEphemeralEnvService(store, quota, clock, simulator);
        testLog.info("==== TEST setup: perOwnerQuota=2 globalQuota=4 ====");
    }


    private static boolean assertNotInterrupted(boolean v) {
        if (Thread.currentThread().isInterrupted()) {
            throw new RuntimeException(new InterruptedException());
        }
        return v;
    }

    private String idem() {
        return UUID.randomUUID().toString();
    }

    @Test
    @DisplayName("同一幂等标识重复提交只生效一次且结论一致")
    void idempotentAcquireReplaysFirstOutcome() {
        String id = idem();
        AcquireResult first = service.acquire(id, "alice", "env-1", 60_000);
        assertFalse(first.replayed());
        assertEquals(LeaseState.ACTIVE, first.lease().state());

        clock.advance(30_000);
        AcquireResult second = service.acquire(id, "alice", "env-1", 60_000);
        AcquireResult third = service.acquire(id, "alice", "env-1", 60_000);

        assertTrue(second.replayed());
        assertTrue(third.replayed());
        assertEquals(first.lease().acquiredAtMillis(), second.lease().acquiredAtMillis());
        assertEquals(first.lease().expireAtMillis(), second.lease().expireAtMillis());
        assertSame(LeaseState.ACTIVE, second.lease().state());
        assertEquals(1, quota.countByOwner("alice"));
        testLog.info("[ASSERT] idempotencyId={} op=ACQUIRE basis=replay-consistent activeCount=1", id);
    }

    @Test
    @DisplayName("被拒绝的首次结论也会被幂等重放，拒绝原因一致")
    void rejectedOutcomeIsAlsoIdempotent() {
        String id = idem();
        service.acquire(idem(), "alice", "env-a", 60_000);
        service.acquire(idem(), "alice", "env-b", 60_000);

        AcquireRejectedException ex1 = assertThrows(AcquireRejectedException.class,
                () -> service.acquire(id, "alice", "env-c", 60_000));
        assertEquals(RejectionReason.OWNER_QUOTA_EXCEEDED, ex1.getReason());

        AcquireRejectedException ex2 = assertThrows(AcquireRejectedException.class,
                () -> service.acquire(id, "alice", "env-c", 60_000));
        assertEquals(ex1.getReason(), ex2.getReason());
        assertEquals(ex1.getMessage(), ex2.getMessage());
        testLog.info("[ASSERT] idempotencyId={} op=ACQUIRE basis=reject-replayed reason={}", id, ex2.getReason());
    }

    @Test
    @DisplayName("不同持有者争用同一环境不得相互覆盖")
    void differentOwnersCannotOverwrite() {
        service.acquire(idem(), "alice", "env-x", 60_000);
        AcquireRejectedException ex = assertThrows(AcquireRejectedException.class,
                () -> service.acquire(idem(), "bob", "env-x", 60_000));
        assertEquals(RejectionReason.ENV_HELD_BY_ANOTHER_OWNER, ex.getReason());
        assertEquals("alice", store.get("env-x").owner());
        assertEquals(LeaseState.ACTIVE, store.get("env-x").state());

        // 原持有者释放后，新持有者才能申请；旧租约进入终态不占配额
        service.release("env-x", "alice");
        AcquireResult got = service.acquire(idem(), "bob", "env-x", 60_000);
        assertEquals("bob", got.lease().owner());
        testLog.info("[ASSERT] op=CONTEND basis=handoff-after-release env=env-x newOwner=bob");
    }

    @Test
    @DisplayName("持有者配额与全局配额分别给出可区分拒绝原因")
    void twoLevelQuotaReasonsAreDistinguishable() {
        service.acquire(idem(), "alice", "a1", 60_000);
        service.acquire(idem(), "alice", "a2", 60_000);
        AcquireRejectedException ownerEx = assertThrows(AcquireRejectedException.class,
                () -> service.acquire(idem(), "alice", "a3", 60_000));
        assertEquals(RejectionReason.OWNER_QUOTA_EXCEEDED, ownerEx.getReason());

        service.acquire(idem(), "bob", "b1", 60_000);
        service.acquire(idem(), "carol", "c1", 60_000);
        AcquireRejectedException globalEx = assertThrows(AcquireRejectedException.class,
                () -> service.acquire(idem(), "dave", "d1", 60_000));
        assertEquals(RejectionReason.GLOBAL_QUOTA_EXCEEDED, globalEx.getReason());
        testLog.info("[ASSERT] op=QUOTA basis=owner-vs-global ownerReason={} globalReason={}",
                ownerEx.getReason(), globalEx.getReason());
    }

    @Test
    @DisplayName("主动释放后配额归还，重复释放不重复扣减")
    void releaseReturnsQuotaAndIsIdempotent() {
        service.acquire(idem(), "alice", "r1", 60_000);
        assertEquals(1, quota.countGlobal());

        ReleaseResult r1 = service.release("r1", "alice");
        assertEquals(LeaseState.RELEASED, r1.finalState());
        assertFalse(r1.replayed());
        assertEquals(0, quota.countByOwner("alice"));
        assertEquals(0, quota.countGlobal());

        ReleaseResult r2 = service.release("r1", "alice");
        assertTrue(r2.replayed());
        assertEquals(LeaseState.RELEASED, r2.finalState());
        assertEquals(0, quota.countGlobal());
        testLog.info("[ASSERT] op=RELEASE basis=double-release-no-double-deduction env=r1 globalQuota=0");
    }

    @Test
    @DisplayName("回收中途失败停留中间态，重试后收敛且配额只释放一次")
    void reclaimFailureThenRetryConverges() {
        service.acquire(idem(), "alice", "f1", 10_000);
        clock.advance(10_001);

        simulator.failFirstAttempts("f1", 2);

        ReclaimFailureException e1 = assertThrows(ReclaimFailureException.class, () -> service.reclaim("f1"));
        assertNotNull(e1);
        assertEquals(LeaseState.RECLAIMING, store.get("f1").state());
        assertEquals(1, quota.countGlobal(), "失败回收不得提前释放配额");

        ReclaimFailureException e2 = assertThrows(ReclaimFailureException.class, () -> service.reclaim("f1"));
        assertEquals(LeaseState.RECLAIMING, store.get("f1").state());
        assertEquals(1, quota.countGlobal());

        ReclaimResult ok = service.reclaim("f1");
        assertEquals(LeaseState.RECLAIMED, ok.finalState());
        assertEquals(0, quota.countGlobal());

        // 迟到 / 重复回收：状态不回退
        ReclaimResult again = service.reclaim("f1");
        assertTrue(again.replayed());
        assertEquals(LeaseState.RECLAIMED, again.finalState());
        assertEquals(0, quota.countGlobal());
        assertEquals(3, simulator.getAttemptCount("f1"));
        testLog.info("[ASSERT] op=RECLAIM basis=fail-twice-then-converge env=f1 attempts=3 quotaReleasedOnce=true");
    }

    @Test
    @DisplayName("未到期回收被拒绝为冲突；过期扫描收敛所有到期租约")
    void activeReclaimConflictsAndSweepConverges() {
        service.acquire(idem(), "alice", "s1", 10_000);
        ConflictException ex = assertThrows(ConflictException.class, () -> service.reclaim("s1"));
        assertTrue(ex.getMessage().contains("尚未到期"));

        clock.advance(10_001);
        List<ReclaimResult> results = service.sweepExpired();
        assertEquals(1, results.size());
        assertEquals(LeaseState.RECLAIMED, results.get(0).finalState());
        testLog.info("[ASSERT] op=SWEEP basis=expired-after-ttl converged=1");
    }

    @Test
    @DisplayName("非持有者不能释放他人租约")
    void nonOwnerCannotRelease() {
        service.acquire(idem(), "alice", "o1", 60_000);
        assertThrows(ConflictException.class, () -> service.release("o1", "bob"));
        assertEquals(LeaseState.ACTIVE, store.get("o1").state());
        testLog.info("[ASSERT] op=RELEASE basis=owner-mismatch env=o1 stateStays=ACTIVE");
    }

    @Test
    @DisplayName("并发申请不同环境：成功数受全局配额约束，结论守恒")
    void concurrentAcquiresRespectGlobalQuota() throws Exception {
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        Set<String> rejectedReasons = ConcurrentHashMap.newKeySet();

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try { start.await(); } catch (InterruptedException ie) { throw new RuntimeException(ie); }
                try {
                    service.acquire(idem(), "owner-" + idx, "env-" + idx, 60_000);
                    accepted.incrementAndGet();
                } catch (AcquireRejectedException ex) {
                    rejectedReasons.add(ex.getReason().name());
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(assertNotInterrupted(pool.awaitTermination(10, TimeUnit.SECONDS)));

        assertEquals(quota.getGlobalLimit(), accepted.get(), "全局配额绝不能超发");
        // 只有全局维度拒绝（每人 1 个环境，持有者维度 2 不会触发）
        assertTrue(rejectedReasons.contains(RejectionReason.GLOBAL_QUOTA_EXCEEDED.name()));
        assertFalse(rejectedReasons.contains(RejectionReason.OWNER_QUOTA_EXCEEDED.name()));
        assertEquals(quota.getGlobalLimit(), quota.countGlobal());
        assertEquals(store.list().size(), quota.getGlobalLimit());
        testLog.info("[ASSERT] op=CONCURRENT_ACQUIRE basis=global-cap accepted={} rejected={} reasons={}",
                accepted.get(), threads - accepted.get(), rejectedReasons);
    }

    @Test
    @DisplayName("同一幂等标识并发提交只有一次生效，全部返回首次结论")
    void concurrentSameIdempotencyAppliesOnce() throws Exception {
        int threads = 12;
        String id = idem();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        Set<Long> acquiredAt = ConcurrentHashMap.newKeySet();
        AtomicInteger replayed = new AtomicInteger();
        AtomicInteger fresh = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    try { start.await(); } catch (InterruptedException ie) { throw new RuntimeException(ie); }
                    AcquireResult r = service.acquire(id, "alice", "dup-env", 60_000);
                    acquiredAt.add(r.lease().acquiredAtMillis());
                    if (r.replayed()) replayed.incrementAndGet(); else fresh.incrementAndGet();
                } catch (Exception ignored) { }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(assertNotInterrupted(pool.awaitTermination(10, TimeUnit.SECONDS)));

        assertEquals(1, fresh.get());
        assertEquals(threads - 1, replayed.get());
        assertEquals(1, acquiredAt.size(), "所有线程必须拿到同一个首次租约");
        assertEquals(1, quota.countByOwner("alice"));
        testLog.info("[ASSERT] idempotencyId={} op=CONCURRENT_ACQUIRE basis=single-effect fresh=1 replayed={}",
                id, threads - 1);
    }

    @Test
    @DisplayName("并发回收同一环境：回收动作只执行一次，状态不回退")
    void concurrentReclaimRunsActionOnce() throws Exception {
        service.acquire(idem(), "alice", "cr1", 10_000);
        clock.advance(10_001);

        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        Set<LeaseState> finals = ConcurrentHashMap.newKeySet();
        AtomicInteger failures = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    try { start.await(); } catch (InterruptedException ie) { throw new RuntimeException(ie); }
                    finals.add(service.reclaim("cr1").finalState());
                } catch (ReclaimFailureException e) {
                    failures.incrementAndGet();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(assertNotInterrupted(pool.awaitTermination(10, TimeUnit.SECONDS)));

        assertEquals(0, failures.get());
        assertEquals(Set.of(LeaseState.RECLAIMED), finals);
        assertEquals(1, simulator.getAttemptCount("cr1"), "互斥回收保证动作只执行一次");
        assertEquals(0, quota.countGlobal());
        testLog.info("[ASSERT] op=CONCURRENT_RECLAIM basis=mutex-single-action env=cr1 attempts=1");
    }

    @Test
    @DisplayName("全流程配额与租约数量守恒")
    void quotaConservationAcrossMixedLifecycle() throws Exception {
        // 4 个持有者并发申请混合释放/回收，最终全局占用必然为 0，且无异常计数漂移
        service.acquire(idem(), "a", "w1", 5_000);
        service.acquire(idem(), "b", "w2", 5_000);
        service.acquire(idem(), "c", "w3", 100_000);
        service.acquire(idem(), "d", "w4", 100_000);
        assertEquals(4, quota.countGlobal());

        service.release("w3", "c");
        service.release("w4", "d");
        clock.advance(5_001);
        service.reclaim("w1");
        service.reclaim("w2");

        assertEquals(0, quota.countGlobal());
        assertEquals(2, store.countByState(LeaseState.RELEASED));
        assertEquals(2, store.countByState(LeaseState.RECLAIMED));
        assertEquals(0, store.countByState(LeaseState.ACTIVE));
        assertEquals(0, store.countByState(LeaseState.RECLAIMING));

        // 终态环境可被重新申请，配额恢复可用
        AcquireResult reused = service.acquire(idem(), "a", "w1", 60_000);
        assertEquals(LeaseState.ACTIVE, reused.lease().state());
        assertEquals(1, quota.countGlobal());
        testLog.info("[ASSERT] op=MIXED_LIFECYCLE basis=conservation released=2 reclaimed=2 reAcquired=1");
    }
}
