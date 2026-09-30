package com.github.xingjianluo.ephemeralenvcontroljava.tests;

import com.github.xingjianluo.ephemeralenvcontroljava.domain.AcquireResult;
import com.github.xingjianluo.ephemeralenvcontroljava.domain.Lease;
import com.github.xingjianluo.ephemeralenvcontroljava.domain.LeaseState;
import com.github.xingjianluo.ephemeralenvcontroljava.domain.ReclaimResult;
import com.github.xingjianluo.ephemeralenvcontroljava.domain.RenewResult;
import com.github.xingjianluo.ephemeralenvcontroljava.exception.ConflictException;
import com.github.xingjianluo.ephemeralenvcontroljava.exception.ConflictReason;
import com.github.xingjianluo.ephemeralenvcontroljava.exception.ReclaimFailureException;
import com.github.xingjianluo.ephemeralenvcontroljava.exception.RenewRejectedException;
import com.github.xingjianluo.ephemeralenvcontroljava.exception.RenewRejectionReason;
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

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 租约续期专项单测。仅依赖本地状态与可控时钟，离线可跑。
 */
class RenewalServiceTest {

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
        testLog.info("==== RENEW TEST setup: perOwnerQuota=2 globalQuota=4 ====");
    }

    private static boolean waitDone(ExecutorService pool) throws InterruptedException {
        pool.shutdown();
        return pool.awaitTermination(15, TimeUnit.SECONDS);
    }

    private String idem() {
        return UUID.randomUUID().toString();
    }

    @Test
    @DisplayName("续期成功：在原到期时刻上顺延，状态仍 ACTIVE，占用数量不变")
    void renewExtendsFromPreviousExpireAndKeepsQuota() {
        service.acquire(idem(), "alice", "e1", 60_000);
        long firstExpire = store.get("e1").expireAtMillis();

        clock.advance(30_000); // 已流逝 30s
        long ownerBefore = quota.countByOwner("alice");
        long globalBefore = quota.countGlobal();

        RenewResult r = service.renew(idem(), "alice", "e1", 20_000);

        assertFalse(r.replayed());
        assertEquals(firstExpire, r.previousExpireAtMillis());
        // 顺延基准是“原到期时刻”，不是 now：流逝的 30s 没有被重新续回来
        assertEquals(firstExpire + 20_000, r.lease().expireAtMillis());
        assertEquals(LeaseState.ACTIVE, r.lease().state());
        assertEquals("alice", r.lease().owner());
        assertEquals(ownerBefore, quota.countByOwner("alice"), "续期不得改变持有者占用数");
        assertEquals(globalBefore, quota.countGlobal(), "续期不得改变全局占用数");

        // 新到期时刻生效：原到期点已不再过期，续到的新到期点才过期
        clock.advance(29_999); // now = 1_000_000+30_000+29_999 = 1_059_999，首次 expire=1_060_000
        assertThrows(ConflictException.class, () -> service.reclaim("e1"), "未到新到期点不能回收");
        clock.advance(20_001); // 越过新到期点 1_080_000
        assertEquals(LeaseState.RECLAIMED, service.reclaim("e1").finalState());
        testLog.info("[ASSERT] op=RENEW basis=extend-from-previous-expire env=e1 {} -> {}",
                firstExpire, r.lease().expireAtMillis());
    }

    @Test
    @DisplayName("多次续期按当前到期时刻累加，不把已流逝时间续回来")
    void multipleRenewsAccumulate() {
        service.acquire(idem(), "alice", "m1", 60_000);
        long base = store.get("m1").expireAtMillis(); // 1_060_000

        clock.advance(20_000);
        RenewResult r1 = service.renew(idem(), "alice", "m1", 10_000);
        assertEquals(base + 10_000, r1.lease().expireAtMillis());

        clock.advance(20_000);
        RenewResult r2 = service.renew(idem(), "alice", "m1", 10_000);
        // 在上一次到期点上继续累加，而不是 now+10s
        assertEquals(base + 20_000, r2.lease().expireAtMillis());
        assertEquals(base + 10_000, r2.previousExpireAtMillis());
        assertEquals(1, quota.countGlobal());
        testLog.info("[ASSERT] op=RENEW basis=accumulate env=m1 expire={}", r2.lease().expireAtMillis());
    }

    @Test
    @DisplayName("同一幂等标识重复续期只生效一次，结论一致")
    void duplicateRenewReplaysFirstOutcome() {
        service.acquire(idem(), "alice", "d1", 60_000);
        String rid = idem();

        clock.advance(10_000);
        RenewResult first = service.renew(rid, "alice", "d1", 30_000);
        long expectedExpire = first.lease().expireAtMillis();

        // 再用别的幂等键续一次，改变当前到期时刻
        clock.advance(10_000);
        service.renew(idem(), "alice", "d1", 30_000);
        assertNotEquals(expectedExpire, store.get("d1").expireAtMillis());

        // 旧幂等键重放：返回首次结论快照，不再顺延
        RenewResult second = service.renew(rid, "alice", "d1", 30_000);
        RenewResult third = service.renew(rid, "alice", "d1", 30_000);
        assertTrue(second.replayed());
        assertTrue(third.replayed());
        assertEquals(expectedExpire, second.lease().expireAtMillis());
        assertEquals(expectedExpire, third.lease().expireAtMillis());
        assertEquals(first.previousExpireAtMillis(), second.previousExpireAtMillis());
        // 只顺延了首次那一次 + 后续另键一次，共两次，不是三次
        assertEquals(1_060_000L + 60_000, store.get("d1").expireAtMillis());
        testLog.info("[ASSERT] idempotencyId={} op=RENEW basis=single-effect replayExpire={}", rid, expectedExpire);
    }

    @Test
    @DisplayName("越权续期确定性拒绝且可重放，环境与配额不受影响")
    void nonOwnerRenewRejectedDeterministically() {
        service.acquire(idem(), "alice", "o1", 60_000);
        long expireBefore = store.get("o1").expireAtMillis();
        String rid = idem();

        RenewRejectedException e1 = assertThrows(RenewRejectedException.class,
                () -> service.renew(rid, "bob", "o1", 10_000));
        assertEquals(RenewRejectionReason.NOT_OWNER, e1.getReason());
        assertFalse(e1.isRetryable());

        RenewRejectedException e2 = assertThrows(RenewRejectedException.class,
                () -> service.renew(rid, "bob", "o1", 10_000));
        assertEquals(e1.getReason(), e2.getReason());
        assertEquals(e1.getMessage(), e2.getMessage());

        assertEquals(LeaseState.ACTIVE, store.get("o1").state());
        assertEquals(expireBefore, store.get("o1").expireAtMillis());
        assertEquals(1, quota.countByOwner("alice"));
        assertEquals(0, quota.countByOwner("bob"));
        testLog.info("[ASSERT] op=RENEW basis=not-owner env=o1 requester=bob stateStays=ACTIVE");
    }

    @Test
    @DisplayName("已过期不能续期、不能救活；拒绝结论可重放")
    void expiredLeaseCannotBeRenewedOrRevived() {
        service.acquire(idem(), "alice", "x1", 10_000);
        clock.advance(10_001);

        String rid = idem();
        RenewRejectedException e1 = assertThrows(RenewRejectedException.class,
                () -> service.renew(rid, "alice", "x1", 60_000));
        assertEquals(RenewRejectionReason.ALREADY_EXPIRED, e1.getReason());
        assertEquals(LeaseState.EXPIRED, store.get("x1").state(), "到期 ACTIVE 被懒标记为 EXPIRED");

        // 再补一次续期也不能救活
        RenewRejectedException e2 = assertThrows(RenewRejectedException.class,
                () -> service.renew(rid, "alice", "x1", 60_000));
        assertEquals(RenewRejectionReason.ALREADY_EXPIRED, e2.getReason());
        assertEquals(LeaseState.EXPIRED, store.get("x1").state());

        // 最终只能走回收收敛
        assertEquals(LeaseState.RECLAIMED, service.reclaim("x1").finalState());
        // 回收后再续同样是确定性拒绝
        assertThrows(RenewRejectedException.class, () -> service.renew(idem(), "alice", "x1", 10_000));
        testLog.info("[ASSERT] op=RENEW basis=already-expired-no-revival env=x1");
    }

    @Test
    @DisplayName("临界到期点 now==expireAt 即判过期，续期不成功")
    void renewAtExactExpiryBoundaryRejected() {
        service.acquire(idem(), "alice", "b1", 10_000);
        clock.advance(10_000); // 恰好到期
        RenewRejectedException e = assertThrows(RenewRejectedException.class,
                () -> service.renew(idem(), "alice", "b1", 10_000));
        assertEquals(RenewRejectionReason.ALREADY_EXPIRED, e.getReason());
        assertEquals(LeaseState.EXPIRED, store.get("b1").state());
        testLog.info("[ASSERT] op=RENEW basis=boundary-now-equals-expire env=b1");
    }

    @Test
    @DisplayName("已释放终态续期被判迟到拒绝，状态不回退")
    void releasedLeaseRenewRejectedAsTerminal() {
        service.acquire(idem(), "alice", "t1", 60_000);
        service.release("t1", "alice");
        RenewRejectedException e = assertThrows(RenewRejectedException.class,
                () -> service.renew(idem(), "alice", "t1", 10_000));
        assertEquals(RenewRejectionReason.ALREADY_TERMINAL, e.getReason());
        assertEquals(LeaseState.RELEASED, store.get("t1").state());
        assertEquals(0, quota.countGlobal());
        testLog.info("[ASSERT] op=RENEW basis=already-terminal env=t1 state=RELEASED");
    }

    @Test
    @DisplayName("回收失败待重试期间续期得到明确可重试结论，绝不假成功；收敛后同键得到终态拒绝")
    void renewDuringReclaimFailureGivesRetryableThenTerminal() {
        service.acquire(idem(), "alice", "f1", 10_000);
        clock.advance(10_001);
        simulator.failFirstAttempts("f1", 2);

        assertThrows(ReclaimFailureException.class, () -> service.reclaim("f1"));
        assertEquals(LeaseState.RECLAIMING, store.get("f1").state());
        assertEquals(1, quota.countGlobal(), "回收失败仍占配额");

        String rid = idem();
        RenewRejectedException during = assertThrows(RenewRejectedException.class,
                () -> service.renew(rid, "alice", "f1", 60_000));
        assertEquals(RenewRejectionReason.TRANSITION_IN_PROGRESS, during.getReason());
        assertTrue(during.isRetryable());
        assertEquals(LeaseState.RECLAIMING, store.get("f1").state(), "续期不得把回收中环境拉回 ACTIVE");
        assertEquals(1, quota.countGlobal());

        // 第二次回收仍失败（仍在 RECLAIMING），续期结论依旧确定
        assertThrows(ReclaimFailureException.class, () -> service.reclaim("f1"));
        assertThrows(RenewRejectedException.class, () -> service.renew(idem(), "alice", "f1", 60_000));

        // 回收收敛到 RECLAIMED 后，用“待重试期间用过的同一幂等键”再请求：
        // 得到的是终态确定性拒绝，而不是旧的进行中结论，更不是成功
        ReclaimResult ok = service.reclaim("f1");
        assertEquals(LeaseState.RECLAIMED, ok.finalState());
        RenewRejectedException after = assertThrows(RenewRejectedException.class,
                () -> service.renew(rid, "alice", "f1", 60_000));
        assertEquals(RenewRejectionReason.ALREADY_TERMINAL, after.getReason());
        assertEquals(0, quota.countGlobal());
        testLog.info("[ASSERT] op=RENEW basis=retryable-during-reclaim-then-terminal env=f1");
    }

    @Test
    @DisplayName("续期与回收并发：先到临界点者生效，续期绝不回退已进入回收/终态的环境")
    void concurrentRenewAndReclaimConverges() throws Exception {
        service.acquire(idem(), "alice", "cr", 10_000);
        clock.advance(10_001);
        simulator.failFirstAttempts("cr", 1); // 第一次回收失败，制造 RECLAIMING 窗口

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        Set<RenewRejectionReason> renewReasons = ConcurrentHashMap.newKeySet();
        AtomicBoolean anyRenewSuccess = new AtomicBoolean();
        AtomicReference<LeaseState> finalState = new AtomicReference<>();

        // 1 个回收线程（先失败一次后成功），其余续期
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try { start.await(); } catch (InterruptedException ie) { throw new RuntimeException(ie); }
                if (idx == 0) {
                    while (true) {
                        try {
                            finalState.set(service.reclaim("cr").finalState());
                            return;
                        } catch (ReclaimFailureException ignored) {
                            // 继续重试直到收敛
                        }
                    }
                } else {
                    try {
                        service.renew(idem(), "alice", "cr", 60_000);
                        anyRenewSuccess.set(true);
                    } catch (RenewRejectedException e) {
                        renewReasons.add(e.getReason());
                    }
                }
            });
        }
        start.countDown();
        assertTrue(waitDone(pool));

        assertFalse(anyRenewSuccess.get(), "回收交错期间任何续期都不允许成功");
        assertTrue(renewReasons.stream().allMatch(
                r -> r == RenewRejectionReason.TRANSITION_IN_PROGRESS
                        || r == RenewRejectionReason.ALREADY_TERMINAL
                        || r == RenewRejectionReason.ALREADY_EXPIRED));
        assertEquals(LeaseState.RECLAIMED, store.get("cr").state());
        assertEquals(LeaseState.RECLAIMED, finalState.get());
        assertEquals(0, quota.countGlobal());
        testLog.info("[ASSERT] op=CONCURRENT_RENEW_RECLAIM basis=no-rollback reasons={}", renewReasons);
    }

    @Test
    @DisplayName("同一幂等标识并发续期只有一次生效，全部返回同一到期时刻")
    void concurrentSameIdempotencyRenewAppliesOnce() throws Exception {
        service.acquire(idem(), "alice", "cd", 60_000);
        long baseExpire = store.get("cd").expireAtMillis();
        String rid = idem();

        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        Set<Long> seenExpire = ConcurrentHashMap.newKeySet();
        AtomicInteger fresh = new AtomicInteger();
        AtomicInteger replayed = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try { start.await(); } catch (InterruptedException ie) { throw new RuntimeException(ie); }
                RenewResult r = service.renew(rid, "alice", "cd", 30_000);
                seenExpire.add(r.lease().expireAtMillis());
                if (r.replayed()) replayed.incrementAndGet(); else fresh.incrementAndGet();
            });
        }
        start.countDown();
        assertTrue(waitDone(pool));

        assertEquals(1, fresh.get());
        assertEquals(threads - 1, replayed.get());
        assertEquals(1, seenExpire.size(), "所有线程必须拿到同一个首次续期结论");
        // 只顺延一次
        assertEquals(baseExpire + 30_000, store.get("cd").expireAtMillis());
        assertEquals(1, quota.countByOwner("alice"));
        testLog.info("[ASSERT] idempotencyId={} op=CONCURRENT_RENEW basis=single-effect expire={}",
                rid, baseExpire + 30_000);
    }

    @Test
    @DisplayName("申请幂等键不能跨操作复用于续期：明确冲突且不顺延")
    void acquireIdempotencyKeyCannotBeReusedForRenew() {
        String shared = idem();
        service.acquire(shared, "alice", "k1", 60_000);
        ConflictException ex = assertThrows(ConflictException.class,
                () -> service.renew(shared, "alice", "k1", 10_000));
        assertEquals(ConflictReason.IDEMPOTENCY_OPERATION_MISMATCH, ex.getReason());
        assertFalse(ex.getReason().isRetryableWithSameRequest(), "必须换键，原样重试无意义");
        assertTrue(ex.getMessage().contains("ACQUIRE"));
        assertEquals(1_060_000L, store.get("k1").expireAtMillis(), "跨操作复用不得顺延");
        testLog.info("[ASSERT] op=RENEW basis=idempotency-namespace-conflict key={}", shared);
    }

    @Test
    @DisplayName("续期与申请/释放交错的并发压力下两级配额上限严格守住")
    void quotaLimitsHoldUnderRenewAcquireReleaseStress() throws Exception {
        // 预置：alice 持有 2 个（达持有者上限），bob 持有 1 个，全局已用 3/4
        service.acquire(idem(), "alice", "a1", 1_000_000);
        service.acquire(idem(), "alice", "a2", 1_000_000);
        service.acquire(idem(), "bob", "b1", 1_000_000);
        assertEquals(3, quota.countGlobal());

        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean violated = new AtomicBoolean();
        AtomicReference<String> violationDetail = new AtomicReference<>();

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try { start.await(); } catch (InterruptedException ie) { throw new RuntimeException(ie); }
                for (int round = 0; round < 200; round++) {
                    // 持续续期 alice 的两个租约（不释放、不新增占用）
                    service.renew(idem(), "alice", (round % 2 == 0) ? "a1" : "a2", 1_000);
                    // bob 释放 b1 后又重新申请，占用在 1/0 之间切换
                    if (round % 50 == 0) {
                        try { service.release("b1", "bob"); } catch (RuntimeException ignored) { }
                    }
                    if (round % 50 == 25) {
                        try { service.acquire(idem(), "bob", "b1", 1_000_000); } catch (RuntimeException ignored) { }
                    }
                    // 其余持有者尝试申请第 4 个全局名额（可能成功可能被拒，但绝不能超发）
                    if (round % 37 == 0) {
                        try { service.acquire(idem(), "carol", "c1", 1_000_000); } catch (RuntimeException ignored) { }
                        if (quota.countGlobal() > quota.getGlobalLimit()) {
                            violated.set(true);
                            violationDetail.set("global=" + quota.countGlobal());
                        }
                    }
                    if (quota.countByOwner("alice") > quota.getPerOwnerLimit()
                            || quota.countGlobal() > quota.getGlobalLimit()) {
                        violated.set(true);
                        violationDetail.set("owner=" + quota.countByOwner("alice")
                                + ",global=" + quota.countGlobal());
                    }
                }
            });
        }
        start.countDown();
        assertTrue(waitDone(pool));

        assertFalse(violated.get(), "并发压力下配额被突破: " + violationDetail.get());
        // 收敛后核对：alice 始终是 2 个 ACTIVE；全局占用 = ACTIVE 租约数（实时统计口径）
        assertEquals(2, quota.countByOwner("alice"));
        assertEquals(quota.countGlobal(),
                store.list().stream().filter(l -> quota.isOccupying(l.state())).count());
        long global = quota.countGlobal();
        assertTrue(global <= quota.getGlobalLimit());
        assertEquals(LeaseState.ACTIVE, store.get("a1").state());
        assertEquals(LeaseState.ACTIVE, store.get("a2").state());
        testLog.info("[ASSERT] op=RENEW_STRESS basis=quota-limits-held finalGlobal={}", global);
    }

    @Test
    @DisplayName("续期参与的全流程数量守恒：续期不增占用，释放/回收后归零，终态可再申请")
    void conservationWithRenewAcrossLifecycle() {
        AcquireResult w1 = service.acquire(idem(), "a", "w1", 5_000);
        service.acquire(idem(), "b", "w2", 100_000);
        assertEquals(2, quota.countGlobal());

        service.renew(idem(), "a", "w1", 5_000); // 续到 1_010_000
        service.renew(idem(), "b", "w2", 10_000);
        assertEquals(2, quota.countGlobal(), "续期前后占用不变");
        assertEquals(2, quota.countByOwner("a") + quota.countByOwner("b"));

        service.release("w2", "b");
        clock.advance(10_001); // 越过 w1 续期后的到期点
        assertEquals(LeaseState.RECLAIMED, service.reclaim("w1").finalState());

        assertEquals(0, quota.countGlobal());
        assertEquals(1, store.countByState(LeaseState.RELEASED));
        assertEquals(1, store.countByState(LeaseState.RECLAIMED));
        assertEquals(0, store.countByState(LeaseState.ACTIVE));

        AcquireResult reused = service.acquire(idem(), "a", "w1", 60_000);
        assertEquals(LeaseState.ACTIVE, reused.lease().state());
        assertEquals(1, quota.countGlobal());
        testLog.info("[ASSERT] op=RENEW_MIXED basis=conservation renewedThenReleasedReclaimed={}", w1.lease().envName());
    }
}
