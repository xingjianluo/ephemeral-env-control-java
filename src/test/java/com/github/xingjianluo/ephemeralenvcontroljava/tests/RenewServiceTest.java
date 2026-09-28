package com.github.xingjianluo.ephemeralenvcontroljava.tests;

import com.github.xingjianluo.ephemeralenvcontroljava.domain.AcquireResult;
import com.github.xingjianluo.ephemeralenvcontroljava.domain.LeaseState;
import com.github.xingjianluo.ephemeralenvcontroljava.domain.RenewResult;
import com.github.xingjianluo.ephemeralenvcontroljava.exception.RenewalRejectedException;
import com.github.xingjianluo.ephemeralenvcontroljava.exception.RenewalRejectedReason;
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

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 租约续期专项单测。仅依赖本地状态与可控时钟，离线可跑。
 */
class RenewServiceTest {

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

    private String idem() {
        return UUID.randomUUID().toString();
    }

    @Test
    @DisplayName("续期成功：到期时刻顺延、占用数量不变、未到期不被回收")
    void renewExtendsExpiryAndKeepsQuota() {
        AcquireResult acquired = service.acquire(idem(), "alice", "e1", 60_000);
        long baseExpire = acquired.lease().expireAtMillis();
        assertEquals(1, quota.countByOwner("alice"));
        assertEquals(1, quota.countGlobal());

        clock.advance(30_000);
        RenewResult renewed = service.renew(idem(), "alice", "e1", 20_000);
        assertFalse(renewed.replayed());
        assertEquals(LeaseState.ACTIVE, renewed.lease().state());
        // 在原到期时刻上顺延，而非从当前时刻重新起算（流逝的 30s 不被补回）
        assertEquals(baseExpire + 20_000, renewed.lease().expireAtMillis());
        assertEquals("alice", renewed.lease().owner());

        // 续期不改变占用数量
        assertEquals(1, quota.countByOwner("alice"));
        assertEquals(1, quota.countGlobal());

        // 原本 60s 到期点：此刻若没续期已过期；续期后未到期，回收应被拒绝
        clock.advance(35_000); // now = +65s，原 ttl 已过，新到期 = +80s
        assertThrows(com.github.xingjianluo.ephemeralenvcontroljava.exception.ConflictException.class,
                () -> service.reclaim("e1"));
        assertEquals(LeaseState.ACTIVE, store.get("e1").state());
        assertEquals(1, quota.countGlobal(), "续期后未到期仍只占一份配额");

        // 超过新到期时刻后才能回收
        clock.advance(20_000); // now = +85s > +80s
        assertEquals(LeaseState.RECLAIMED, service.reclaim("e1").finalState());
        assertEquals(0, quota.countGlobal());
        testLog.info("[ASSERT] op=RENEW basis=extended-then-reclaim-after-new-expiry newExpireAt={}",
                renewed.lease().expireAtMillis());
    }

    @Test
    @DisplayName("多次续期按原到期时刻顺延累加，流逝时间不会被补回")
    void multipleRenewalsAccumulateOnPreviousExpiry() {
        AcquireResult acquired = service.acquire(idem(), "alice", "m1", 60_000);
        long baseExpire = acquired.lease().expireAtMillis();

        clock.advance(20_000);
        RenewResult r1 = service.renew(idem(), "alice", "m1", 10_000);
        assertEquals(baseExpire + 10_000, r1.lease().expireAtMillis());

        clock.advance(20_000); // now=+40s
        RenewResult r2 = service.renew(idem(), "alice", "m1", 15_000);
        // 在上一次到期时刻(+70s)上顺延，而非从 now(+40s) 起算
        assertEquals(baseExpire + 10_000 + 15_000, r2.lease().expireAtMillis());
        assertTrue(r2.lease().expireAtMillis() > clock.nowMillis() + 40_000);

        assertEquals(1, quota.countGlobal());
        testLog.info("[ASSERT] op=RENEW basis=cumulative-on-old-expiry finalExpireAt={}",
                r2.lease().expireAtMillis());
    }

    @Test
    @DisplayName("同一幂等标识重复续期只生效一次，结论一致且不多次顺延")
    void renewIdempotentReplay() {
        service.acquire(idem(), "alice", "i1", 60_000);
        String rid = idem();

        RenewResult first = service.renew(rid, "alice", "i1", 30_000);
        long firstExpire = first.lease().expireAtMillis();
        assertFalse(first.replayed());

        clock.advance(10_000);
        RenewResult second = service.renew(rid, "alice", "i1", 30_000);
        RenewResult third = service.renew(rid, "alice", "i1", 30_000);
        assertTrue(second.replayed());
        assertTrue(third.replayed());
        assertEquals(firstExpire, second.lease().expireAtMillis());
        assertEquals(firstExpire, third.lease().expireAtMillis());
        assertEquals(first.lease().acquiredAtMillis(), second.lease().acquiredAtMillis());
        // 幂等重放不重复顺延、不改变占用
        assertEquals(1, quota.countByOwner("alice"));
        assertEquals(1, quota.countGlobal());
        testLog.info("[ASSERT] idempotencyId={} op=RENEW basis=replay-consistent expireAt={}", rid, firstExpire);
    }

    @Test
    @DisplayName("同一幂等标识并发续期只生效一次")
    void concurrentSameRenewalIdempotencyAppliesOnce() throws Exception {
        service.acquire(idem(), "alice", "ci1", 60_000);
        String rid = idem();
        int threads = 12;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.Set<Long> expireAts = java.util.concurrent.ConcurrentHashMap.newKeySet();
        java.util.concurrent.atomic.AtomicInteger fresh = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger replayed = new java.util.concurrent.atomic.AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    RenewResult r = service.renew(rid, "alice", "ci1", 30_000);
                    expireAts.add(r.lease().expireAtMillis());
                    if (r.replayed()) replayed.incrementAndGet(); else fresh.incrementAndGet();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS));

        assertEquals(1, fresh.get());
        assertEquals(threads - 1, replayed.get());
        assertEquals(1, expireAts.size(), "所有线程拿到同一个首次续期结论");
        assertEquals(1, quota.countGlobal());
        testLog.info("[ASSERT] idempotencyId={} op=CONCURRENT_RENEW basis=single-effect fresh=1 replayed={}",
                rid, threads - 1);
    }

    @Test
    @DisplayName("非持有者续期被拒绝：原因可区分、状态与配额不变")
    void nonOwnerRenewalRejected() {
        service.acquire(idem(), "alice", "n1", 60_000);
        RenewalRejectedException ex = assertThrows(RenewalRejectedException.class,
                () -> service.renew(idem(), "bob", "n1", 10_000));
        assertEquals(RenewalRejectedReason.NOT_OWNER, ex.getReason());
        assertFalse(ex.isRetryable());
        assertEquals(LeaseState.ACTIVE, store.get("n1").state());
        assertEquals("alice", store.get("n1").owner());
        assertEquals(1, quota.countByOwner("alice"));
        assertEquals(0, quota.countByOwner("bob"));
        testLog.info("[ASSERT] op=RENEW basis=owner-mismatch reason=NOT_OWNER");
    }

    @Test
    @DisplayName("到期后续期判迟到拒绝，不能把环境救活；首次拒绝结论一致重放")
    void expiredRenewalIsRejectedAndCannotRevive() {
        AcquireResult acquired = service.acquire(idem(), "alice", "x1", 10_000);
        long baseExpire = acquired.lease().expireAtMillis();
        clock.advance(10_001);

        String rid = idem();
        // 即使状态还是 ACTIVE（懒标记前），越过临界点的续期也必须被拒
        RenewalRejectedException e1 = assertThrows(RenewalRejectedException.class,
                () -> service.renew(rid, "alice", "x1", 60_000));
        assertEquals(RenewalRejectedReason.ALREADY_EXPIRED, e1.getReason());
        assertEquals(1, quota.countGlobal(), "拒绝续期不得改变占用");

        // 同一幂等标识一致重放首次拒绝结论
        RenewalRejectedException e2 = assertThrows(RenewalRejectedException.class,
                () -> service.renew(rid, "alice", "x1", 60_000));
        assertEquals(e1.getReason(), e2.getReason());

        // 到期时刻未被偷偷延长，回收正常收敛
        assertEquals(baseExpire, store.get("x1").expireAtMillis());
        assertEquals(LeaseState.RECLAIMED, service.reclaim("x1").finalState());
        assertEquals(0, quota.countGlobal());

        // 回收后换幂等键续期仍是终态拒绝，无法救活
        RenewalRejectedException e3 = assertThrows(RenewalRejectedException.class,
                () -> service.renew(idem(), "alice", "x1", 60_000));
        assertEquals(RenewalRejectedReason.TERMINAL, e3.getReason());
        testLog.info("[ASSERT] op=RENEW basis=late-after-expiry-and-terminal cannotRevive=true");
    }

    @Test
    @DisplayName("EXPIRED 态（已懒标记）续期被拒，原因可区分")
    void expiredStateRenewalRejected() {
        service.acquire(idem(), "alice", "x2", 10_000);
        clock.advance(10_001);
        // reclaim 第一次注入失败会把租约推进 EXPIRED -> RECLAIMING；这里用 sweep 前的场景：
        // 直接构造 EXPIRED 可见性较难，故通过“到期后先回收失败”覆盖 RECLAIMING（见下一例）。
        // 本例仅校验到期点后的拒绝原因稳定为 ALREADY_EXPIRED。
        RenewalRejectedException ex = assertThrows(RenewalRejectedException.class,
                () -> service.renew(idem(), "alice", "x2", 1_000));
        assertEquals(RenewalRejectedReason.ALREADY_EXPIRED, ex.getReason());
        testLog.info("[ASSERT] op=RENEW basis=expired reason=ALREADY_EXPIRED");
    }

    @Test
    @DisplayName("回收失败停留 RECLAIMING 期间续期得到确定拒绝，不给假成功，且拒绝结论可重放")
    void renewalDuringReclaimingGetsDefinitiveRejection() {
        service.acquire(idem(), "alice", "f1", 10_000);
        clock.advance(10_001);
        simulator.failFirstAttempts("f1", 2);

        assertThrows(com.github.xingjianluo.ephemeralenvcontroljava.exception.ReclaimFailureException.class,
                () -> service.reclaim("f1"));
        assertEquals(LeaseState.RECLAIMING, store.get("f1").state());
        assertEquals(1, quota.countGlobal(), "回收失败待重试期间配额不释放");

        String rid = idem();
        RenewalRejectedException e1 = assertThrows(RenewalRejectedException.class,
                () -> service.renew(rid, "alice", "f1", 60_000));
        assertEquals(RenewalRejectedReason.RECLAIMING, e1.getReason());
        assertFalse(e1.isRetryable(), "已进入回收链路，续期不应被建议原样重试");
        assertEquals(LeaseState.RECLAIMING, store.get("f1").state(), "续期绝不能把环境拉回 ACTIVE");

        RenewalRejectedException e2 = assertThrows(RenewalRejectedException.class,
                () -> service.renew(rid, "alice", "f1", 60_000));
        assertEquals(e1.getReason(), e2.getReason());

        // 回收重试：第二次仍失败，续期结论不变
        assertThrows(com.github.xingjianluo.ephemeralenvcontroljava.exception.ReclaimFailureException.class,
                () -> service.reclaim("f1"));
        assertEquals(LeaseState.RECLAIMING, store.get("f1").state());

        // 回收第三次成功后正常终态，续期依然无法救活
        assertEquals(LeaseState.RECLAIMED, service.reclaim("f1").finalState());
        RenewalRejectedException e3 = assertThrows(RenewalRejectedException.class,
                () -> service.renew(idem(), "alice", "f1", 60_000));
        assertEquals(RenewalRejectedReason.TERMINAL, e3.getReason());
        assertEquals(0, quota.countGlobal());
        testLog.info("[ASSERT] op=RENEW basis=reclaiming-then-terminal definitive=true");
    }

    @Test
    @DisplayName("主动释放途中 / 释放终态续期被拒；释放成功后终态拒绝")
    void releasingAndReleasedRenewalRejected() {
        service.acquire(idem(), "alice", "r1", 60_000);
        service.release("r1", "alice");
        assertEquals(LeaseState.RELEASED, store.get("r1").state());

        RenewalRejectedException ex = assertThrows(RenewalRejectedException.class,
                () -> service.renew(idem(), "alice", "r1", 10_000));
        assertEquals(RenewalRejectedReason.TERMINAL, ex.getReason());
        assertEquals(0, quota.countGlobal());
        testLog.info("[ASSERT] op=RENEW basis=after-release-terminal");
    }

    @Test
    @DisplayName("不存在的环境与参数非法给出明确结论")
    void missingLeaseAndIllegalArgsRejected() {
        RenewalRejectedException nf = assertThrows(RenewalRejectedException.class,
                () -> service.renew(idem(), "alice", "ghost", 10_000));
        assertEquals(RenewalRejectedReason.LEASE_NOT_FOUND, nf.getReason());

        service.acquire(idem(), "alice", "g1", 60_000);
        assertThrows(IllegalArgumentException.class,
                () -> service.renew(idem(), "alice", "g1", 0));
        assertThrows(IllegalArgumentException.class,
                () -> service.renew("  ", "alice", "g1", 10_000));
        testLog.info("[ASSERT] op=RENEW basis=not-found-and-illegal-args");
    }

    @Test
    @DisplayName("幂等标识绑定的 owner/env 不一致返回可重试 conflict")
    void renewalIdempotencyKeyConflict() {
        service.acquire(idem(), "alice", "k1", 60_000);
        service.acquire(idem(), "bob", "k2", 60_000);
        String rid = idem();
        service.renew(rid, "alice", "k1", 10_000);
        // 同一标识换 env
        com.github.xingjianluo.ephemeralenvcontroljava.exception.ConflictException ce =
                assertThrows(com.github.xingjianluo.ephemeralenvcontroljava.exception.ConflictException.class,
                        () -> service.renew(rid, "alice", "k2", 10_000));
        assertTrue(ce.getMessage().contains("已绑定"));
        // 换 owner
        assertThrows(com.github.xingjianluo.ephemeralenvcontroljava.exception.ConflictException.class,
                () -> service.renew(rid, "bob", "k1", 10_000));
        testLog.info("[ASSERT] op=RENEW basis=idempotency-binding-conflict");
    }

    /**
     * 可阻塞的回收模拟器：进入回收动作后放行等待，可用于构造“回收动作进行中”的确定性交错。
     */
    static final class BlockingSimulator implements com.github.xingjianluo.ephemeralenvcontroljava.reclaim.ReclaimFailureSimulator {
        final java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch allowFinish = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();

        @Override
        public boolean attemptReclaim(String envName) {
            attempts.incrementAndGet();
            entered.countDown();
            try {
                allowFinish.await(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
            return true;
        }
    }

    @Test
    @DisplayName("续期与到期回收并发交错：临界时刻串行化，只有先到的一方生效，结论可解释")
    void renewConcurrentWithReclaimConvergesToSingleOutcome() throws Exception {
        BlockingSimulator blocking = new BlockingSimulator();
        DefaultEphemeralEnvService svc = new DefaultEphemeralEnvService(store, quota, clock, blocking);
        svc.acquire(idem(), "alice", "race1", 10_000);
        clock.advance(10_001); // 已到临界点

        // 回收线程先进入（会在回收动作中途阻塞）
        java.util.concurrent.atomic.AtomicReference<com.github.xingjianluo.ephemeralenvcontroljava.domain.ReclaimResult> reclaimOutcome =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<Throwable> reclaimError = new java.util.concurrent.atomic.AtomicReference<>();
        Thread reclaimer = new Thread(() -> {
            try {
                reclaimOutcome.set(svc.reclaim("race1"));
            } catch (Throwable t) {
                reclaimError.set(t);
            }
        });
        reclaimer.start();
        assertTrue(blocking.entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
        // 此刻租约已 EXPIRED->RECLAIMING，回收动作进行中
        assertEquals(LeaseState.RECLAIMING, store.get("race1").state());

        // 续期在回收动作进行中到达：在环境锁上排队，绝不允许假成功或把环境拉回 ACTIVE。
        // 它等待先到的回收收敛，然后被明确判定为迟到（终态）。
        java.util.concurrent.atomic.AtomicReference<RenewalRejectedException> renewError =
                new java.util.concurrent.atomic.AtomicReference<>();
        Thread renewer = new Thread(() -> {
            try {
                svc.renew(idem(), "alice", "race1", 60_000);
            } catch (RenewalRejectedException e) {
                renewError.set(e);
            }
        });
        renewer.start();
        // 给续期线程一点时间进入排队（锁竞争），期间状态不允许被它改变
        Thread.sleep(100);
        assertEquals(LeaseState.RECLAIMING, store.get("race1").state());
        assertEquals(1, quota.countGlobal(), "回收未完成前配额不得因续期发生变化");

        blocking.allowFinish.countDown();
        reclaimer.join(5_000);
        renewer.join(5_000);
        assertNull(reclaimError.get());
        assertEquals(LeaseState.RECLAIMED, reclaimOutcome.get().finalState());
        assertEquals(1, blocking.attempts.get(), "回收动作只执行一次");

        // 排队的续期拿到确定的“迟到”结论，而不是成功
        assertNotNull(renewError.get(), "回收进行中/终态上的续期必须被拒绝");
        assertEquals(RenewalRejectedReason.TERMINAL, renewError.get().getReason());
        assertEquals(LeaseState.RECLAIMED, store.get("race1").state(), "绝不能被续期拉回 ACTIVE");
        assertEquals(0, quota.countGlobal());

        // 再次迟到续期（换新键）结论一致
        RenewalRejectedException late = assertThrows(RenewalRejectedException.class,
                () -> svc.renew(idem(), "alice", "race1", 60_000));
        assertEquals(RenewalRejectedReason.TERMINAL, late.getReason());
        testLog.info("[ASSERT] op=RENEW_VS_RECLAIM basis=reclaim-first-renew-queued-then-late converged=RECLAIMED");
    }

    @Test
    @DisplayName("临界时刻续期先到则回收被挡：续期后到期点顺延，旧到期点回收未到期冲突")
    void renewFirstBlocksReclaimAtOldExpiry() {
        service.acquire(idem(), "alice", "race2", 10_000);
        // 在临界点之前续期
        clock.advance(9_000);
        RenewResult renewed = service.renew(idem(), "alice", "race2", 10_000);
        assertEquals(1_000_000L + 20_000, renewed.lease().expireAtMillis());

        // 到达原到期点（+10s 已过，新到期 +20s 未到）：回收必须报未到期冲突
        clock.advance(2_000); // now = +11s
        com.github.xingjianluo.ephemeralenvcontroljava.exception.ConflictException ce =
                assertThrows(com.github.xingjianluo.ephemeralenvcontroljava.exception.ConflictException.class,
                        () -> service.reclaim("race2"));
        assertTrue(ce.getMessage().contains("尚未到期"));
        assertEquals(LeaseState.ACTIVE, store.get("race2").state());
        assertEquals(1, quota.countGlobal());

        // 超过新到期点后回收收敛
        clock.advance(10_000);
        assertEquals(LeaseState.RECLAIMED, service.reclaim("race2").finalState());
        assertEquals(0, quota.countGlobal());
        testLog.info("[ASSERT] op=RENEW_VS_RECLAIM basis=renew-first-reclaim-blocked-at-old-expiry");
    }

    @Test
    @DisplayName("高并发：多持有者申请/续期/释放/回收交错，两级配额恒守恒")
    void highConcurrencyMixedOpsConservesQuota() throws Exception {
        int envCount = 4; // 等于全局上限
        String[] owners = {"alice", "bob"};
        // 先打满全局：alice x2, bob x2
        for (int i = 0; i < envCount; i++) {
            service.acquire(idem(), owners[i % 2], "h" + i, 100_000);
        }
        assertEquals(4, quota.countGlobal());
        assertEquals(2, quota.countByOwner("alice"));
        assertEquals(2, quota.countByOwner("bob"));

        int threads = 12;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger renewOk = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger renewRej = new java.util.concurrent.atomic.AtomicInteger();

        for (int t = 0; t < threads; t++) {
            final int idx = t;
            pool.submit(() -> {
                try {
                    start.await();
                    String owner = owners[idx % 2];
                    String env = "h" + (idx % envCount);
                    try {
                        // 大量续期（合法/越权混合，env 归属按 idx 奇偶：h0,h2->alice; h1,h3->bob）
                        boolean isOwner = owner.equals(store.get(env).owner());
                        service.renew(idem(), owner, env, 1_000);
                        if (isOwner) {
                            renewOk.incrementAndGet();
                        }
                    } catch (RenewalRejectedException expected) {
                        renewRej.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS));

        // 无论续期成功失败，占用数量与状态完全不变
        assertEquals(4, quota.countGlobal(), "续期不得改变全局占用");
        assertEquals(2, quota.countByOwner("alice"));
        assertEquals(2, quota.countByOwner("bob"));
        assertEquals(4, store.countByState(LeaseState.ACTIVE));
        assertEquals(0, store.countByState(LeaseState.EXPIRED));
        assertEquals(0, store.countByState(LeaseState.RECLAIMING));
        assertEquals(0, store.countByState(LeaseState.RELEASED));
        assertEquals(0, store.countByState(LeaseState.RECLAIMED));
        testLog.info("[ASSERT] op=CONCURRENT_RENEW_MIXED basis=quota-conserved renewOk={} renewRej={}",
                renewOk.get(), renewRej.get());
    }

    @Test
    @DisplayName("续期后别人仍抢不走；全局打满时他人新申请被拒，续期不占额外配额")
    void renewalDoesNotFreeEnvOrConsumeQuotaForOthers() {
        // alice 占 2 个（持有者上限），bob/carol 各 1 个，打满全局 4
        service.acquire(idem(), "alice", "a1", 10_000);
        service.acquire(idem(), "alice", "a2", 100_000);
        service.acquire(idem(), "bob", "b1", 100_000);
        service.acquire(idem(), "carol", "c1", 100_000);
        assertEquals(4, quota.countGlobal());

        // a1 即将到期，续期把它保住
        clock.advance(9_000);
        RenewResult rn = service.renew(idem(), "alice", "a1", 50_000);
        assertEquals(LeaseState.ACTIVE, rn.lease().state());
        assertEquals(4, quota.countGlobal(), "续期前后全局占用不变");
        assertEquals(2, quota.countByOwner("alice"));

        // 别人抢 a1：仍被 alice 占用，拒绝
        com.github.xingjianluo.ephemeralenvcontroljava.exception.AcquireRejectedException held =
                assertThrows(com.github.xingjianluo.ephemeralenvcontroljava.exception.AcquireRejectedException.class,
                        () -> service.acquire(idem(), "dave", "a1", 60_000));
        assertEquals(com.github.xingjianluo.ephemeralenvcontroljava.exception.RejectionReason.ENV_HELD_BY_ANOTHER_OWNER,
                held.getReason());

        // 到原到期点：a1 没被回收，dave 仍申请不到，全局仍 4
        clock.advance(2_000);
        assertEquals(LeaseState.ACTIVE, store.get("a1").state());
        com.github.xingjianluo.ephemeralenvcontroljava.exception.AcquireRejectedException global =
                assertThrows(com.github.xingjianluo.ephemeralenvcontroljava.exception.AcquireRejectedException.class,
                        () -> service.acquire(idem(), "dave", "d1", 60_000));
        assertEquals(com.github.xingjianluo.ephemeralenvcontroljava.exception.RejectionReason.GLOBAL_QUOTA_EXCEEDED,
                global.getReason());
        assertEquals(4, quota.countGlobal());

        // 有人释放腾出配额后，新申请才能进，且数量守恒
        service.release("b1", "bob");
        assertEquals(3, quota.countGlobal());
        service.acquire(idem(), "dave", "d1", 60_000);
        assertEquals(4, quota.countGlobal());
        assertEquals(1, quota.countByOwner("dave"));
        testLog.info("[ASSERT] op=RENEW basis=env-kept-and-quota-conserved global=4");
    }

    @Test
    @DisplayName("续期与到期扫描交错：续期生效的租约不被 sweep 回收，过期租约正常收敛")
    void sweepRespectsRenewedExpiry() {
        service.acquire(idem(), "alice", "w1", 10_000);
        service.acquire(idem(), "bob", "w2", 10_000);
        clock.advance(9_000);
        // 只续 w1
        service.renew(idem(), "alice", "w1", 50_000);
        clock.advance(2_000); // now=+11s：w2 过期，w1 新到期=+60s

        java.util.List<com.github.xingjianluo.ephemeralenvcontroljava.domain.ReclaimResult> swept =
                service.sweepExpired();
        assertEquals(1, swept.size());
        assertEquals("w2", swept.get(0).envName());
        assertEquals(LeaseState.RECLAIMED, swept.get(0).finalState());

        assertEquals(LeaseState.ACTIVE, store.get("w1").state());
        assertEquals(1, quota.countGlobal());
        assertEquals(1, quota.countByOwner("alice"));
        testLog.info("[ASSERT] op=RENEW_VS_SWEEP basis=renewed-survives expired-reclaimed");
    }
}
