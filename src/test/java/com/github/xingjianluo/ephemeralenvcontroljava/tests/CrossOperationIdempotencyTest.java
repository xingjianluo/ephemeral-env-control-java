package com.github.xingjianluo.ephemeralenvcontroljava.tests;

import com.github.xingjianluo.ephemeralenvcontroljava.domain.AcquireResult;
import com.github.xingjianluo.ephemeralenvcontroljava.domain.LeaseState;
import com.github.xingjianluo.ephemeralenvcontroljava.domain.RenewResult;
import com.github.xingjianluo.ephemeralenvcontroljava.exception.AcquireRejectedException;
import com.github.xingjianluo.ephemeralenvcontroljava.exception.ConflictException;
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
 * 跨操作幂等标识隔离专项单测：
 * 申请与续期的幂等记录属于各自独立的命名空间，同一标识跨操作复用时，
 * 两个方向都必须收敛为明确、可区分的冲突（ConflictException），
 * 且不得真的生效一次申请或续期——租约状态、到期时刻、占用数量全部不变。
 * 仅依赖本地状态与可控时钟，离线可跑。
 */
class CrossOperationIdempotencyTest {

    private static final Logger testLog = LoggerFactory.getLogger("TEST");

    static final class MutableClock implements Clock {
        private final AtomicLong t = new AtomicLong(1_000_000L);
        @Override public long nowMillis() { return t.get(); }
        void advance(long delta) { t.addAndGet(delta); }
    }

    private InMemoryLeaseStore store;
    private QuotaService quota;
    private MutableClock clock;
    private DefaultEphemeralEnvService service;

    @BeforeEach
    void setUp() {
        store = new InMemoryLeaseStore();
        clock = new MutableClock();
        quota = new QuotaService(store, 2, 4);
        service = new DefaultEphemeralEnvService(store, quota, clock, new CountingFailureSimulator());
    }

    private String idem() {
        return UUID.randomUUID().toString();
    }

    @Test
    @DisplayName("续期成功后的标识再发起申请：明确冲突，不当作申请重放，不产生新占用")
    void renewAcceptedThenAcquireSameIdConflicts() {
        service.acquire(idem(), "alice", "e1", 60_000);
        long expireAfterRenew = store.get("e1").expireAtMillis() + 20_000;

        String renewId = idem();
        RenewResult renewed = service.renew(renewId, "alice", "e1", 20_000);
        assertFalse(renewed.replayed());
        assertEquals(expireAfterRenew, store.get("e1").expireAtMillis());
        long ownerBefore = quota.countByOwner("alice");
        long globalBefore = quota.countGlobal();
        int leaseCountBefore = store.list().size();

        // 同一标识跨操作复用：必须是可区分的冲突，而不是 400 参数错误，也不是“申请成功重放”。
        ConflictException ex = assertThrows(ConflictException.class,
                () -> service.acquire(renewId, "alice", "e1", 60_000));
        assertTrue(ex.getMessage().contains(renewId), "冲突信息应携带幂等标识以便排查");

        // 不得真的生效一次申请：租约状态、到期时刻、占用数量、租约总数全部不变。
        assertEquals(LeaseState.ACTIVE, store.get("e1").state());
        assertEquals(expireAfterRenew, store.get("e1").expireAtMillis(), "不得改变到期时刻");
        assertEquals(ownerBefore, quota.countByOwner("alice"), "不得改变持有者占用数");
        assertEquals(globalBefore, quota.countGlobal(), "不得改变全局占用数");
        assertEquals(leaseCountBefore, store.list().size(), "不得创建新租约");

        // 原续期结论仍可按原操作重放，记录未被申请侧串用。
        RenewResult replay = service.renew(renewId, "alice", "e1", 20_000);
        assertTrue(replay.replayed());
        assertEquals(expireAfterRenew, replay.lease().expireAtMillis());
        testLog.info("[ASSERT] op=CROSS_IDEM basis=renew-accepted-then-acquire id={} decision=CONFLICT", renewId);
    }

    @Test
    @DisplayName("续期被拒后的标识再发起申请：明确冲突（而非 400 参数错误），不产生任何占用")
    void renewRejectedThenAcquireSameIdConflicts() {
        service.acquire(idem(), "alice", "e1", 60_000);
        long expireBefore = store.get("e1").expireAtMillis();

        // bob 不是持有者，续期被确定性拒绝，结论落幂等记录。
        String renewId = idem();
        RenewRejectedException rejected = assertThrows(RenewRejectedException.class,
                () -> service.renew(renewId, "bob", "e1", 20_000));
        assertEquals(RenewRejectionReason.NOT_OWNER, rejected.getReason());
        long globalBefore = quota.countGlobal();
        int leaseCountBefore = store.list().size();

        // 同一标识再发起申请：必须收敛为可区分的冲突，绝不能抛 IllegalArgumentException（400）。
        ConflictException ex = assertThrows(ConflictException.class,
                () -> service.acquire(renewId, "bob", "e2", 60_000));
        assertTrue(ex.getMessage().contains(renewId));

        // 不得真的生效一次申请：不创建新租约、不改变任何占用与既有租约。
        assertEquals(globalBefore, quota.countGlobal(), "不得改变全局占用数");
        assertEquals(0, quota.countByOwner("bob"), "冲突路径不得为 bob 产生占用");
        assertEquals(leaseCountBefore, store.list().size(), "不得创建新租约");
        assertNull(store.get("e2"), "冲突路径不得建立 e2 租约");
        assertEquals(expireBefore, store.get("e1").expireAtMillis(), "既有租约到期时刻不变");

        // 原续期拒绝结论仍可按原操作重放，且仍是续期语义的可区分结论。
        RenewRejectedException replay = assertThrows(RenewRejectedException.class,
                () -> service.renew(renewId, "bob", "e1", 20_000));
        assertEquals(RenewRejectionReason.NOT_OWNER, replay.getReason());
        testLog.info("[ASSERT] op=CROSS_IDEM basis=renew-rejected-then-acquire id={} decision=CONFLICT", renewId);
    }

    @Test
    @DisplayName("申请成功后的标识再发起续期：明确冲突，不改变到期时刻与占用")
    void acquireAcceptedThenRenewSameIdConflicts() {
        String acquireId = idem();
        AcquireResult acquired = service.acquire(acquireId, "alice", "e1", 60_000);
        assertFalse(acquired.replayed());
        long expireBefore = store.get("e1").expireAtMillis();
        long ownerBefore = quota.countByOwner("alice");
        long globalBefore = quota.countGlobal();

        // 同一标识跨操作复用：必须是可区分的冲突，而不是把申请当成续期重放。
        ConflictException ex = assertThrows(ConflictException.class,
                () -> service.renew(acquireId, "alice", "e1", 20_000));
        assertTrue(ex.getMessage().contains(acquireId));

        // 不得真的生效一次续期：到期时刻、状态、占用数量全部不变。
        assertEquals(expireBefore, store.get("e1").expireAtMillis(), "不得改变到期时刻");
        assertEquals(LeaseState.ACTIVE, store.get("e1").state());
        assertEquals(ownerBefore, quota.countByOwner("alice"));
        assertEquals(globalBefore, quota.countGlobal());

        // 原申请结论仍可按原操作重放，记录未被续期侧串用。
        AcquireResult replay = service.acquire(acquireId, "alice", "e1", 60_000);
        assertTrue(replay.replayed());
        assertEquals(expireBefore, replay.lease().expireAtMillis());
        testLog.info("[ASSERT] op=CROSS_IDEM basis=acquire-accepted-then-renew id={} decision=CONFLICT", acquireId);
    }

    @Test
    @DisplayName("申请被拒后的标识再发起续期：明确冲突，不改变租约与配额")
    void acquireRejectedThenRenewSameIdConflicts() {
        service.acquire(idem(), "alice", "e1", 60_000);
        long expireBefore = store.get("e1").expireAtMillis();

        // 环境被 alice 占用，bob 的申请被确定性拒绝，结论落幂等记录。
        String acquireId = idem();
        assertThrows(AcquireRejectedException.class,
                () -> service.acquire(acquireId, "bob", "e1", 60_000));
        long globalBefore = quota.countGlobal();

        // 同一标识再发起续期：必须收敛为可区分的冲突，而不是重放申请拒绝或真的续期。
        ConflictException ex = assertThrows(ConflictException.class,
                () -> service.renew(acquireId, "bob", "e1", 20_000));
        assertTrue(ex.getMessage().contains(acquireId));

        // 不得真的生效一次续期：到期时刻、状态、占用数量全部不变。
        assertEquals(expireBefore, store.get("e1").expireAtMillis(), "不得改变到期时刻");
        assertEquals(LeaseState.ACTIVE, store.get("e1").state());
        assertEquals(globalBefore, quota.countGlobal(), "不得改变全局占用数");

        // 原申请拒绝结论仍可按原操作重放，且仍是申请语义的可区分结论。
        assertThrows(AcquireRejectedException.class,
                () -> service.acquire(acquireId, "bob", "e1", 60_000));
        testLog.info("[ASSERT] op=CROSS_IDEM basis=acquire-rejected-then-renew id={} decision=CONFLICT", acquireId);
    }
}
