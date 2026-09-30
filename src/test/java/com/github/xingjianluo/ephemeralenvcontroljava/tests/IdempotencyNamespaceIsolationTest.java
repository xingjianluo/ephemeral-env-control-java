package com.github.xingjianluo.ephemeralenvcontroljava.tests;

import com.github.xingjianluo.ephemeralenvcontroljava.domain.AcquireResult;
import com.github.xingjianluo.ephemeralenvcontroljava.domain.Lease;
import com.github.xingjianluo.ephemeralenvcontroljava.domain.LeaseState;
import com.github.xingjianluo.ephemeralenvcontroljava.domain.RenewResult;
import com.github.xingjianluo.ephemeralenvcontroljava.exception.AcquireRejectedException;
import com.github.xingjianluo.ephemeralenvcontroljava.exception.ConflictException;
import com.github.xingjianluo.ephemeralenvcontroljava.exception.ConflictReason;
import com.github.xingjianluo.ephemeralenvcontroljava.exception.RejectionReason;
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

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 申请（ACQUIRE）与续期（RENEW）幂等标识命名空间隔离的专项单测。
 *
 * <p>覆盖四个方向的跨操作复用：续期成功/被拒后再申请、申请成功/被拒后再续期，
 * 断言全部收敛成同一个明确、可区分的冲突结论
 * （{@link ConflictReason#IDEMPOTENCY_OPERATION_MISMATCH}），
 * 且不真的生效一次申请或续期：租约状态、到期时刻、占用数量一律不变。
 * 仅依赖本地状态与可控时钟，离线可跑。
 */
class IdempotencyNamespaceIsolationTest {

    private static final Logger testLog = LoggerFactory.getLogger("TEST");

    private InMemoryLeaseStore store;
    private QuotaService quota;
    private DefaultEphemeralEnvService service;
    private final AtomicLong now = new AtomicLong(1_000_000L);

    @BeforeEach
    void setUp() {
        store = new InMemoryLeaseStore();
        Clock clock = now::get;
        quota = new QuotaService(store, 2, 4);
        service = new DefaultEphemeralEnvService(store, quota, clock, new CountingFailureSimulator());
    }

    private String idem() {
        return UUID.randomUUID().toString();
    }

    private void assertOperationMismatch(ConflictException ex) {
        assertEquals(ConflictReason.IDEMPOTENCY_OPERATION_MISMATCH, ex.getReason());
        assertFalse(ex.getReason().isRetryableWithSameRequest(),
                "跨操作复用是确定性冲突，原样重试无意义，必须换键");
        testLog.info("[ASSERT] conflict reason={} message={}", ex.getReason(), ex.getMessage());
    }

    // ---------------- 续期 → 申请 ----------------

    @Test
    @DisplayName("续期成功后的标识再发起申请：明确冲突，不新建租约、不顺延、不动配额")
    void renewSuccessThenAcquireWithSameKeyIsOperationMismatch() {
        service.acquire(idem(), "alice", "e1", 60_000);
        Lease leaseBefore = store.get("e1");
        long expireBefore = leaseBefore.expireAtMillis();
        long ownerBefore = quota.countByOwner("alice");
        long globalBefore = quota.countGlobal();

        String shared = idem();
        now.addAndGet(10_000);
        RenewResult renewed = service.renew(shared, "alice", "e1", 30_000);
        assertFalse(renewed.replayed());
        long renewedExpire = store.get("e1").expireAtMillis();
        assertEquals(expireBefore + 30_000, renewedExpire);

        // 同一个标识（甚至同 owner/env）再发起申请：只能是冲突，绝不能当成申请成功重放
        ConflictException ex1 = assertThrows(ConflictException.class,
                () -> service.acquire(shared, "alice", "e1", 60_000));
        assertOperationMismatch(ex1);
        assertTrue(ex1.getMessage().contains("RENEW"));

        // 结论稳定：再来一次仍是同一冲突
        ConflictException ex2 = assertThrows(ConflictException.class,
                () -> service.acquire(shared, "alice", "e1", 60_000));
        assertEquals(ex1.getReason(), ex2.getReason());

        // 不产生任何申请侧效果
        Lease leaseAfter = store.get("e1");
        assertSame(leaseAfter, store.get("e1"));
        assertEquals(LeaseState.ACTIVE, leaseAfter.state());
        assertEquals(renewedExpire, leaseAfter.expireAtMillis(), "到期时刻停在首次续期的结果，不再变化");
        assertEquals("alice", leaseAfter.owner());
        assertEquals(ownerBefore, quota.countByOwner("alice"), "占用数不变");
        assertEquals(globalBefore, quota.countGlobal(), "全局占用不变");
        assertEquals(1, store.list().size(), "没有新建任何租约");
        testLog.info("[ASSERT] op=RENEW->ACQUIRE basis=namespace-conflict-no-effect env=e1");
    }

    @Test
    @DisplayName("续期被拒后的标识再发起申请：明确冲突而非参数错误(400)，不生效、不动配额")
    void renewRejectedThenAcquireWithSameKeyIsOperationMismatch() {
        service.acquire(idem(), "alice", "r1", 60_000);
        long expireBefore = store.get("r1").expireAtMillis();

        String shared = idem();
        // bob 非持有者续期：确定性拒绝，拒绝记录归属 RENEW
        RenewRejectedException rejected = assertThrows(RenewRejectedException.class,
                () -> service.renew(shared, "bob", "r1", 10_000));
        assertEquals(RenewRejectionReason.NOT_OWNER, rejected.getReason());

        long globalBefore = quota.countGlobal();
        long aliceBefore = quota.countByOwner("alice");
        long bobBefore = quota.countByOwner("bob");

        // 同一标识（同 owner/env，与首次续期请求完全一致）再发起申请：
        // 必须是可区分的跨操作冲突，而不是把 NOT_OWNER 当申请拒绝码解析导致的 400
        ConflictException ex = assertThrows(ConflictException.class,
                () -> service.acquire(shared, "bob", "r1", 60_000));
        assertOperationMismatch(ex);
        assertTrue(ex.getMessage().contains("RENEW"));

        // 申请侧没有任何效果：bob 没有拿到租约，alice 租约原样
        assertEquals(LeaseState.ACTIVE, store.get("r1").state());
        assertEquals("alice", store.get("r1").owner());
        assertEquals(expireBefore, store.get("r1").expireAtMillis());
        assertEquals(aliceBefore, quota.countByOwner("alice"));
        assertEquals(bobBefore, quota.countByOwner("bob"));
        assertEquals(globalBefore, quota.countGlobal());
        assertEquals(1, store.list().size());
        testLog.info("[ASSERT] op=RENEW(rejected)->ACQUIRE basis=namespace-conflict-not-400 env=r1");
    }

    // ---------------- 申请 → 续期 ----------------

    @Test
    @DisplayName("申请成功后的标识再发起续期：明确冲突，不顺延、不改变状态与配额")
    void acquireSuccessThenRenewWithSameKeyIsOperationMismatch() {
        String shared = idem();
        AcquireResult acquired = service.acquire(shared, "alice", "n1", 60_000);
        assertFalse(acquired.replayed());
        long expireBefore = store.get("n1").expireAtMillis();

        now.addAndGet(10_000);
        long globalBefore = quota.countGlobal();

        ConflictException ex1 = assertThrows(ConflictException.class,
                () -> service.renew(shared, "alice", "n1", 30_000));
        assertOperationMismatch(ex1);
        assertTrue(ex1.getMessage().contains("ACQUIRE"));

        ConflictException ex2 = assertThrows(ConflictException.class,
                () -> service.renew(shared, "alice", "n1", 30_000));
        assertEquals(ex1.getReason(), ex2.getReason());

        assertEquals(LeaseState.ACTIVE, store.get("n1").state());
        assertEquals(expireBefore, store.get("n1").expireAtMillis(), "续期绝不能顺延");
        assertEquals(1, quota.countByOwner("alice"));
        assertEquals(globalBefore, quota.countGlobal());
        testLog.info("[ASSERT] op=ACQUIRE->RENEW basis=namespace-conflict-no-effect env=n1");
    }

    @Test
    @DisplayName("申请被拒后的标识再发起续期：明确冲突，不顺延、不救活、不动配额")
    void acquireRejectedThenRenewWithSameKeyIsOperationMismatch() {
        // 打满 alice 的持有者配额
        service.acquire(idem(), "alice", "p1", 60_000);
        service.acquire(idem(), "alice", "p2", 60_000);

        String shared = idem();
        AcquireRejectedException rejected = assertThrows(AcquireRejectedException.class,
                () -> service.acquire(shared, "alice", "p3", 60_000));
        assertEquals(RejectionReason.OWNER_QUOTA_EXCEEDED, rejected.getReason());

        long globalBefore = quota.countGlobal();
        // p1 租约存在且 ACTIVE，续期本可生效，但标识属于被拒的申请
        long expireBefore = store.get("p1").expireAtMillis();
        now.addAndGet(10_000);

        ConflictException ex = assertThrows(ConflictException.class,
                () -> service.renew(shared, "alice", "p1", 30_000));
        assertOperationMismatch(ex);
        assertTrue(ex.getMessage().contains("ACQUIRE"));

        assertEquals(LeaseState.ACTIVE, store.get("p1").state());
        assertEquals(expireBefore, store.get("p1").expireAtMillis(), "没有顺延");
        assertNull(store.get("p3"), "被拒申请没有创建租约，冲突重放也不会补建");
        assertEquals(2, quota.countByOwner("alice"));
        assertEquals(globalBefore, quota.countGlobal());
        testLog.info("[ASSERT] op=ACQUIRE(rejected)->RENEW basis=namespace-conflict-no-effect");
    }

    @Test
    @DisplayName("跨操作冲突与同键参数不一致冲突可相互区分")
    void operationMismatchIsDistinguishableFromRequestMismatch() {
        String shared = idem();
        service.acquire(shared, "alice", "z1", 60_000);

        // 同操作但参数变了：请求不一致冲突
        ConflictException requestMismatch = assertThrows(ConflictException.class,
                () -> service.acquire(shared, "bob", "z1", 60_000));
        assertEquals(ConflictReason.IDEMPOTENCY_REQUEST_MISMATCH, requestMismatch.getReason());

        // 换另一类操作：操作命名空间冲突
        ConflictException operationMismatch = assertThrows(ConflictException.class,
                () -> service.renew(shared, "alice", "z1", 10_000));
        assertEquals(ConflictReason.IDEMPOTENCY_OPERATION_MISMATCH, operationMismatch.getReason());
        testLog.info("[ASSERT] basis=two-distinguishable-conflicts {} vs {}",
                requestMismatch.getReason(), operationMismatch.getReason());
    }
}
