package com.github.xingjianluo.ephemeralenvcontroljava.tests;

import com.github.xingjianluo.ephemeralenvcontroljava.model.DecisionCode;
import com.github.xingjianluo.ephemeralenvcontroljava.model.LeaseResult;
import com.github.xingjianluo.ephemeralenvcontroljava.model.LeaseStatus;
import com.github.xingjianluo.ephemeralenvcontroljava.recycler.SimulatedRecycler;
import com.github.xingjianluo.ephemeralenvcontroljava.service.EnvLeaseManager;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 并发场景：同键并发申请、异主争用、并发回收；结论一致且配额守恒。 */
class ConcurrentLifecycleTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrentLifecycleTest.class);

    private EnvLeaseManager newManager(MutableClock clock,
                                      com.github.xingjianluo.ephemeralenvcontroljava.recycler.EnvRecycler recycler,
                                      int holderLimit, int globalLimit) {
        return new EnvLeaseManager(clock, recycler, holderLimit, globalLimit, 1000L);
    }

    @Test
    void concurrentSameKeyApply_onlyOneTakesEffect() throws Exception {
        MutableClock clock = new MutableClock();
        SimulatedRecycler recycler = new SimulatedRecycler();
        EnvLeaseManager m = newManager(clock, recycler, 10, 100);

        int threads = 32;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<LeaseResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return m.apply("same-key", "alice", "env-c1", null);
            }));
        }
        start.countDown();

        Set<DecisionCode> codes = new HashSet<>();
        for (Future<LeaseResult> f : futures) {
            codes.add(f.get(10, TimeUnit.SECONDS).getCode());
        }
        pool.shutdown();
        log.info("[用例] op=APPLY idempotencyKey=same-key 判定依据=32线程同键并发 决策集合={} 全局已用={}",
                codes, m.quotaSnapshot().globalUsed());

        // 所有线程看到同一个冻结结论；配额与租约只增加一次
        assertEquals(Set.of(DecisionCode.APPLY_SUCCESS), codes);
        assertEquals(1, m.quotaSnapshot().globalUsed());
        assertEquals(1, m.quotaSnapshot().leaseCount());
        assertTrue(m.auditConservation());
    }

    @Test
    void concurrentContention_differentHolders_onlyOneWins() throws Exception {
        MutableClock clock = new MutableClock();
        SimulatedRecycler recycler = new SimulatedRecycler();
        EnvLeaseManager m = newManager(clock, recycler, 10, 100);

        int n = 16;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<LeaseResult>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            final String holder = "holder-" + i;
            futures.add(pool.submit(() -> {
                start.await();
                return m.apply("key-" + holder, holder, "env-contended", null);
            }));
        }
        start.countDown();

        AtomicInteger success = new AtomicInteger();
        AtomicInteger occupiedByOther = new AtomicInteger();
        Set<String> winners = ConcurrentHashMap.newKeySet();
        for (Future<LeaseResult> f : futures) {
            LeaseResult r = f.get(10, TimeUnit.SECONDS);
            if (r.getCode() == DecisionCode.APPLY_SUCCESS) {
                success.incrementAndGet();
                winners.add(r.getLeaseSnapshot().getHolder());
            } else if (r.getCode() == DecisionCode.ENV_OCCUPIED_BY_OTHER) {
                occupiedByOther.incrementAndGet();
            }
        }
        pool.shutdown();
        log.info("[用例] op=APPLY env=env-contended 判定依据=16持有者并发争用 成功者={} 被拒={} 赢家集合={}",
                success.get(), occupiedByOther.get(), winners);

        assertEquals(1, success.get());
        assertEquals(n - 1, occupiedByOther.get());
        assertEquals(1, winners.size());
        assertEquals(1, m.quotaSnapshot().globalUsed());
        assertTrue(m.auditConservation());
    }

    @Test
    void concurrentRecycle_oneInFlight_othersGetConflict_andQuotaDeductedOnce() throws Exception {
        MutableClock clock = new MutableClock();
        BlockingRecycler recycler = new BlockingRecycler();
        EnvLeaseManager m = newManager(clock, recycler, 10, 100);

        m.apply("c-apply", "alice", "env-c2", null);

        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        // 第一个线程发起会阻塞在回收动作上
        Future<LeaseResult> first = pool.submit(() -> {
            start.await();
            return m.release("c-rel-1", "alice", "env-c2");
        });
        start.countDown();
        recycler.awaitEntered(); // 确保第一次回收已在途

        // 在途期间并发重复回收：必须冲突拒绝，且不得再次调用回收器
        List<Future<LeaseResult>> concurrent = new ArrayList<>();
        for (int i = 2; i <= 4; i++) {
            final String key = "c-rel-" + i;
            concurrent.add(pool.submit(() -> m.release(key, "alice", "env-c2")));
        }
        Set<DecisionCode> conflictCodes = new HashSet<>();
        for (Future<LeaseResult> f : concurrent) {
            conflictCodes.add(f.get(10, TimeUnit.SECONDS).getCode());
        }
        log.info("[用例] op=RELEASE env=env-c2 判定依据=回收在途时并发重复 决策集合={} 回收器调用次数={}",
                conflictCodes, recycler.invocationCount());

        assertEquals(Set.of(DecisionCode.ALREADY_RELEASING), conflictCodes);
        assertEquals(1, recycler.invocationCount());

        recycler.releaseGate();
        LeaseResult done = first.get(10, TimeUnit.SECONDS);
        pool.shutdown();
        log.info("[用例] op=RELEASE idempotencyKey=c-rel-1 判定依据=在途回收放行完成 decision={} 全局已用={}",
                done.getCode(), m.quotaSnapshot().globalUsed());

        assertEquals(DecisionCode.RELEASE_SUCCESS, done.getCode());
        assertEquals(LeaseStatus.RELEASED, m.getLease("env-c2").getStatus());
        assertEquals(0, m.quotaSnapshot().globalUsed());
        // 放行后的迟到并发尝试不会再执行第二次外部回收
        assertEquals(1, recycler.invocationCount());
        assertTrue(m.auditConservation());
    }

    @Test
    void mixedConcurrentApplies_underGlobalQuota_neverExceedsLimit() throws Exception {
        MutableClock clock = new MutableClock();
        SimulatedRecycler recycler = new SimulatedRecycler();
        int globalLimit = 5;
        EnvLeaseManager m = newManager(clock, recycler, globalLimit, globalLimit);

        int threads = 40;
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<LeaseResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                start.await();
                // 每个持有者最多 1 个环境，压力全部落在全局配额上
                return m.apply("mix-" + idx, "holder-" + idx, "env-" + idx, null);
            }));
        }
        start.countDown();

        AtomicInteger success = new AtomicInteger();
        AtomicInteger globalRejected = new AtomicInteger();
        for (Future<LeaseResult> f : futures) {
            LeaseResult r = f.get(10, TimeUnit.SECONDS);
            if (r.getCode() == DecisionCode.APPLY_SUCCESS) {
                success.incrementAndGet();
            } else if (r.getCode() == DecisionCode.GLOBAL_QUOTA_EXCEEDED) {
                globalRejected.incrementAndGet();
            }
        }
        pool.shutdown();
        log.info("[用例] op=APPLY 判定依据=40线程争用全局配额5 成功={} 全局超额拒绝={} 全局已用={}",
                success.get(), globalRejected.get(), m.quotaSnapshot().globalUsed());

        assertEquals(globalLimit, success.get());
        assertEquals(threads - globalLimit, globalRejected.get());
        assertEquals(globalLimit, m.quotaSnapshot().globalUsed());
        assertEquals(globalLimit, m.quotaSnapshot().leaseCount());
        assertTrue(m.auditConservation());
    }
}
