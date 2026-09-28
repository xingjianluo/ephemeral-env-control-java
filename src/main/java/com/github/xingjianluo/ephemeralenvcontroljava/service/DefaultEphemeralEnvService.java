package com.github.xingjianluo.ephemeralenvcontroljava.service;

import com.github.xingjianluo.ephemeralenvcontroljava.domain.AcquireResult;
import com.github.xingjianluo.ephemeralenvcontroljava.domain.Lease;
import com.github.xingjianluo.ephemeralenvcontroljava.domain.LeaseState;
import com.github.xingjianluo.ephemeralenvcontroljava.domain.ReclaimResult;
import com.github.xingjianluo.ephemeralenvcontroljava.domain.ReleaseResult;
import com.github.xingjianluo.ephemeralenvcontroljava.domain.RenewResult;
import com.github.xingjianluo.ephemeralenvcontroljava.exception.AcquireRejectedException;
import com.github.xingjianluo.ephemeralenvcontroljava.exception.ConflictException;
import com.github.xingjianluo.ephemeralenvcontroljava.exception.ReclaimFailureException;
import com.github.xingjianluo.ephemeralenvcontroljava.exception.RejectionReason;
import com.github.xingjianluo.ephemeralenvcontroljava.exception.RenewalRejectedException;
import com.github.xingjianluo.ephemeralenvcontroljava.exception.RenewalRejectedReason;
import com.github.xingjianluo.ephemeralenvcontroljava.quota.QuotaService;
import com.github.xingjianluo.ephemeralenvcontroljava.reclaim.ReclaimFailureSimulator;
import com.github.xingjianluo.ephemeralenvcontroljava.store.IdempotencyRecord;
import com.github.xingjianluo.ephemeralenvcontroljava.store.LeaseStore;
import com.github.xingjianluo.ephemeralenvcontroljava.store.RenewalIdempotencyRecord;
import com.github.xingjianluo.ephemeralenvcontroljava.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 临时环境生命周期管控的本地实现。
 *
 * 并发模型：
 * - 申请走全局申请锁（判定“配额 + 环境占用 + 建租约 + 写幂等结论”为一个临界区），
 *   保证并发申请下结论确定、配额不超发；
 * - 释放 / 回收走每环境互斥锁，配合存储层 CAS，保证重复、迟到、并发回收不回退状态。
 */
@Service
public class DefaultEphemeralEnvService implements EphemeralEnvService {

    private static final Logger log = LoggerFactory.getLogger(DefaultEphemeralEnvService.class);

    private final LeaseStore store;
    private final QuotaService quotaService;
    private final Clock clock;
    private final ReclaimFailureSimulator simulator;

    private final ReentrantLock acquireLock = new ReentrantLock();
    private final ConcurrentHashMap<String, Object> envLocks = new ConcurrentHashMap<>();

    public DefaultEphemeralEnvService(LeaseStore store, QuotaService quotaService,
                                      Clock clock, ReclaimFailureSimulator simulator) {
        this.store = store;
        this.quotaService = quotaService;
        this.clock = clock;
        this.simulator = simulator;
    }

    private Object envLock(String envName) {
        return envLocks.computeIfAbsent(envName, k -> new Object());
    }

    // ------------------------------------------------------------------
    // 申请
    // ------------------------------------------------------------------

    @Override
    public AcquireResult acquire(String idempotencyId, String owner, String envName, long ttlMillis) {
        validateAcquireArgs(idempotencyId, owner, envName, ttlMillis);

        // 快速路径：直接命中首次结论。
        IdempotencyRecord existing = store.getIdempotency(idempotencyId);
        if (existing != null) {
            return replayAcquire(existing, owner, envName);
        }

        acquireLock.lock();
        try {
            // 双重检查：持锁期间可能已由其他线程写入首次结论。
            existing = store.getIdempotency(idempotencyId);
            if (existing != null) {
                return replayAcquire(existing, owner, envName);
            }

            long now = clock.nowMillis();

            // 1) 环境争用判定
            Lease current = store.get(envName);
            if (current != null && quotaService.isOccupying(current.state())) {                if (!owner.equals(current.owner())) {
                    return rejectAcquire(idempotencyId, owner, envName, now,
                            RejectionReason.ENV_HELD_BY_ANOTHER_OWNER,
                            "环境 " + envName + " 正被持有者 " + current.owner() + " 占用");
                }
                // 同一持有者重复申请同一环境：返回当前租约，幂等成功，不重复占用配额。
                IdempotencyRecord rec = acceptRecord(idempotencyId, owner, envName, now, current,
                        "同持有者已持有该环境，返回既有租约");
                store.putIdempotencyIfAbsent(rec);
                log.info("op=ACQUIRE idempotencyId={} env={} owner={} decision=ACCEPT_EXISTING leaseState={} basis={}",
                        idempotencyId, envName, owner, current.state(), rec.rejectionMessage());
                return new AcquireResult(current, false, rec.rejectionMessage());
            }

            // 2) 持有者配额（先报更具体的维度）
            long ownerUsed = quotaService.countByOwner(owner);
            if (ownerUsed >= quotaService.getPerOwnerLimit()) {
                return rejectAcquire(idempotencyId, owner, envName, now,
                        RejectionReason.OWNER_QUOTA_EXCEEDED,
                        "持有者配额超限: used=" + ownerUsed + ", limit=" + quotaService.getPerOwnerLimit());
            }

            // 3) 全局配额
            long globalUsed = quotaService.countGlobal();
            if (globalUsed >= quotaService.getGlobalLimit()) {
                return rejectAcquire(idempotencyId, owner, envName, now,
                        RejectionReason.GLOBAL_QUOTA_EXCEEDED,
                        "全局配额超限: used=" + globalUsed + ", limit=" + quotaService.getGlobalLimit());
            }

            // 4) 建立租约：空环境可直接创建；终态（RELEASED/RECLAIMED）环境允许被重新申请。
            Lease lease = new Lease(envName, owner, idempotencyId, now, now + ttlMillis, LeaseState.ACTIVE);
            Lease placed = store.compareAndUpdate(envName,
                    l -> l == null || !quotaService.isOccupying(l.state()),
                    l -> lease);
            if (placed == null) {
                // 临界区内状态被并发流转（理论上仅当与释放/回收竞争时），按争用处理。
                Lease winner = store.get(envName);
                if (winner != null && owner.equals(winner.owner())) {
                    IdempotencyRecord rec = acceptRecord(idempotencyId, owner, envName, now, winner,
                            "并发申请同环境，复用既有租约");
                    store.putIdempotencyIfAbsent(rec);
                    return new AcquireResult(winner, false, rec.rejectionMessage());
                }
                return rejectAcquire(idempotencyId, owner, envName, now,
                        RejectionReason.ENV_HELD_BY_ANOTHER_OWNER,
                        "并发申请竞争失败，环境被 " + (winner == null ? "其他请求" : winner.owner()) + " 占用");
            }

            IdempotencyRecord rec = acceptRecord(idempotencyId, owner, envName, now, lease, "首次申请成功");
            store.putIdempotencyIfAbsent(rec);
            log.info("op=ACQUIRE idempotencyId={} env={} owner={} decision=ACCEPT_NEW leaseState=ACTIVE "
                    + "basis=ownerQuota {}/{} globalQuota {}/{}",
                    idempotencyId, envName, owner,
                    ownerUsed + 1, quotaService.getPerOwnerLimit(),
                    globalUsed + 1, quotaService.getGlobalLimit());
            return new AcquireResult(lease, false, "首次申请成功");
        } finally {
            acquireLock.unlock();
        }
    }

    private AcquireResult replayAcquire(IdempotencyRecord rec, String owner, String envName) {
        if (!rec.owner().equals(owner) || !rec.envName().equals(envName)) {
            // 幂等键复用但请求体不同：冲突，调用方需换键重试。
            throw new ConflictException("幂等标识 " + rec.idempotencyId()
                    + " 已绑定 owner=" + rec.owner() + ", env=" + rec.envName());
        }
        if (!rec.accepted()) {
            log.info("op=ACQUIRE_REPLAY idempotencyId={} env={} owner={} decision=REJECT_REPLAY basis={}",
                    rec.idempotencyId(), envName, owner, rec.rejectionCode());
            throw new AcquireRejectedException(RejectionReason.valueOf(rec.rejectionCode()),
                    rec.rejectionMessage());
        }
        log.info("op=ACQUIRE_REPLAY idempotencyId={} env={} owner={} decision=ACCEPT_REPLAY basis=idempotentRecord",
                rec.idempotencyId(), envName, owner);
        return new AcquireResult(rec.lease(), true, "幂等重放: " + rec.rejectionMessage());
    }

    private AcquireResult rejectAcquire(String idempotencyId, String owner, String envName, long now,
                                        RejectionReason reason, String message) {
        IdempotencyRecord rec = new IdempotencyRecord(
                idempotencyId, owner, envName, false, null, reason.name(), message, now);
        store.putIdempotencyIfAbsent(rec);
        log.info("op=ACQUIRE idempotencyId={} env={} owner={} decision=REJECT reason={} basis={}",
                idempotencyId, envName, owner, reason, message);
        throw new AcquireRejectedException(reason, message);
    }

    private IdempotencyRecord acceptRecord(String idempotencyId, String owner, String envName,
                                           long now, Lease lease, String note) {
        return new IdempotencyRecord(idempotencyId, owner, envName, true, lease, null, note, now);
    }

    private void validateAcquireArgs(String idempotencyId, String owner, String envName, long ttlMillis) {
        if (idempotencyId == null || idempotencyId.isBlank()) {
            throw new IllegalArgumentException("idempotencyId 不能为空");
        }
        if (owner == null || owner.isBlank()) {
            throw new IllegalArgumentException("owner 不能为空");
        }
        if (envName == null || envName.isBlank()) {
            throw new IllegalArgumentException("envName 不能为空");
        }
        if (ttlMillis <= 0) {
            throw new IllegalArgumentException("ttlMillis 必须为正数");
        }
    }

    // ------------------------------------------------------------------
    // 释放
    // ------------------------------------------------------------------

    @Override
    public ReleaseResult release(String envName, String owner) {
        if (envName == null || envName.isBlank() || owner == null || owner.isBlank()) {
            throw new IllegalArgumentException("envName / owner 不能为空");
        }
        synchronized (envLock(envName)) {
            for (;;) {
                Lease lease = store.get(envName);
                if (lease == null) {
                    throw new ConflictException("环境 " + envName + " 不存在租约");
                }

                if (!owner.equals(lease.owner())) {
                    throw new ConflictException("环境 " + envName + " 不属于持有者 " + owner);
                }

                switch (lease.state()) {
                    case RELEASED -> {
                        log.info("op=RELEASE env={} owner={} decision=REPLAY basis=alreadyReleased", envName, owner);
                        return new ReleaseResult(envName, LeaseState.RELEASED, true, "租约已释放（重复请求）");
                    }
                    case RECLAIMED -> {
                        log.info("op=RELEASE env={} owner={} decision=REPLAY basis=alreadyReclaimed", envName, owner);
                        return new ReleaseResult(envName, LeaseState.RECLAIMED, true, "租约已被回收（迟到释放）");
                    }
                    case RELEASING, RECLAIMING -> throw new ConflictException(
                            "环境 " + envName + " 正处于 " + lease.state() + "，请稍后重试");
                    case EXPIRED -> throw new ConflictException(
                            "环境 " + envName + " 已过期，应走回收流程");
                    case ACTIVE -> {
                        if (clock.nowMillis() >= lease.expireAtMillis()) {
                            // 已到期但状态尚未流转：拒绝释放，引导回收。
                            throw new ConflictException("环境 " + envName + " 租约已到期，应走回收流程");
                        }
                        Lease updated = store.compareAndUpdate(envName,
                                l -> l.state() == LeaseState.ACTIVE && owner.equals(l.owner()),
                                l -> l.withState(LeaseState.RELEASING));
                        if (updated == null) {
                            continue; // 并发变更，重新判定
                        }
                        // 释放动作即本地状态收敛（无外部依赖），直接进入终态。
                        Lease finished = store.compareAndUpdate(envName,
                                l -> l.state() == LeaseState.RELEASING,
                                l -> l.withState(LeaseState.RELEASED));
                        if (finished == null) {
                            // 并发回收抢先进入 RECLAIMING：不回退，以回收为准。
                            Lease now2 = store.get(envName);
                            log.info("op=RELEASE env={} owner={} decision=DEFER basis=concurrentReclaim state={}",
                                    envName, owner, now2.state());
                            return new ReleaseResult(envName, now2.state(), true,
                                    "释放与回收并发，以回收结论为准: " + now2.state());
                        }
                        log.info("op=RELEASE env={} owner={} decision=RELEASED basis=activeLeaseReleased", envName, owner);
                        return new ReleaseResult(envName, LeaseState.RELEASED, false, "释放成功");
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 续期
    // ------------------------------------------------------------------

    @Override
    public RenewResult renew(String idempotencyId, String owner, String envName, long extendMillis) {
        validateRenewArgs(idempotencyId, owner, envName, extendMillis);

        // 快速路径：直接命中首次结论。
        RenewalIdempotencyRecord existing = store.getRenewalIdempotency(idempotencyId);
        if (existing != null) {
            return replayRenew(existing, owner, envName);
        }

        // 与释放 / 回收共用每环境互斥锁：续期与到期懒标记、回收动作串行化，
        // 先到达临界点的一方生效，后到方只能看到已收敛结论或被明确判为迟到。
        synchronized (envLock(envName)) {
            // 双重检查：持锁期间可能已由其他线程写入首次结论。
            existing = store.getRenewalIdempotency(idempotencyId);
            if (existing != null) {
                return replayRenew(existing, owner, envName);
            }

            Lease lease = store.get(envName);
            if (lease == null) {
                return rejectRenew(idempotencyId, owner, envName,
                        RenewalRejectedReason.LEASE_NOT_FOUND,
                        "环境 " + envName + " 不存在租约，无法续期");
            }

            // 先判持有者身份（与 release 语义一致）：非持有者无论租约处于什么状态都不能续期。
            if (!owner.equals(lease.owner())) {
                return rejectRenew(idempotencyId, owner, envName,
                        RenewalRejectedReason.NOT_OWNER,
                        "环境 " + envName + " 不属于持有者 " + owner + "，实际持有者 " + lease.owner());
            }

            // 再按当前状态判定：任何非 ACTIVE 的状态都给出确定结论，
            // 绝不允许把已进入回收 / 终态的环境拉回可用。
            switch (lease.state()) {
                case EXPIRED -> {
                    return rejectRenew(idempotencyId, owner, envName,
                            RenewalRejectedReason.ALREADY_EXPIRED,
                            "环境 " + envName + " 已到期进入过期态，续期迟到，不能救活");
                }
                case RELEASING -> {
                    return rejectRenew(idempotencyId, owner, envName,
                            RenewalRejectedReason.RELEASING,
                            "环境 " + envName + " 正在主动释放，请稍后重试");
                }
                case RECLAIMING -> {
                    // 含“回收中途失败等待重试”：回收链路已占先，续期明确判迟到，不给假成功。
                    return rejectRenew(idempotencyId, owner, envName,
                            RenewalRejectedReason.RECLAIMING,
                            "环境 " + envName + " 已进入回收流程（含回收失败待重试），续期被拒绝");
                }
                case RELEASED, RECLAIMED -> {
                    return rejectRenew(idempotencyId, owner, envName,
                            RenewalRejectedReason.TERMINAL,
                            "环境 " + envName + " 已处于终态 " + lease.state() + "，不能续期");
                }
                case ACTIVE -> { /* 继续判定 */ }
            }

            long now = clock.nowMillis();
            if (now >= lease.expireAtMillis()) {
                // ACTIVE 但已越过到期临界点：回收 / 扫描可随时懒标记，续期迟到，禁止“救活”。
                return rejectRenew(idempotencyId, owner, envName,
                        RenewalRejectedReason.ALREADY_EXPIRED,
                        "环境 " + envName + " 租约已到期，续期迟到，不能救活");
            }

            // CAS 断言“仍是同一持有者的 ACTIVE 且仍未到期”，原子顺延到期时刻。
            // 续期不触碰 state，因此占用数量、配额统计完全不变。
            long oldExpireAt = lease.expireAtMillis();
            Lease renewed = store.compareAndUpdate(envName,
                    l -> l.state() == LeaseState.ACTIVE
                            && owner.equals(l.owner())
                            && clock.nowMillis() < l.expireAtMillis(),
                    l -> l.withExtendedExpiration(extendMillis));
            if (renewed == null) {
                // 持锁期间状态被并发流转（理论上仅在与回收竞争时），重新收敛为明确结论。
                Lease winner = store.get(envName);
                if (winner != null && winner.state() != LeaseState.ACTIVE) {
                    RenewalRejectedReason reason = switch (winner.state()) {
                        case EXPIRED -> RenewalRejectedReason.ALREADY_EXPIRED;
                        case RELEASING -> RenewalRejectedReason.RELEASING;
                        case RECLAIMING -> RenewalRejectedReason.RECLAIMING;
                        default -> RenewalRejectedReason.TERMINAL;
                    };
                    return rejectRenew(idempotencyId, owner, envName, reason,
                            "并发流转抢先，环境 " + envName + " 当前状态 " + winner.state());
                }
                if (winner == null) {
                    return rejectRenew(idempotencyId, owner, envName,
                            RenewalRejectedReason.LEASE_NOT_FOUND,
                            "并发流转后环境 " + envName + " 租约不存在");
                }
                return rejectRenew(idempotencyId, owner, envName,
                        RenewalRejectedReason.NOT_OWNER,
                        "并发流转后环境 " + envName + " 持有者已变化");
            }

            RenewalIdempotencyRecord rec = new RenewalIdempotencyRecord(
                    idempotencyId, owner, envName, true, renewed,
                    null, "续期成功", false, now);
            store.putRenewalIdempotencyIfAbsent(rec);
            log.info("op=RENEW idempotencyId={} env={} owner={} decision=ACCEPT_NEW "
                            + "basis=activeLeaseExtended oldExpireAt={} newExpireAt={} extend={} quotaUnchanged global={}/{}",
                    idempotencyId, envName, owner, oldExpireAt, renewed.expireAtMillis(),
                    extendMillis, quotaService.countGlobal(), quotaService.getGlobalLimit());
            return new RenewResult(renewed, false, "续期成功");
        }
    }

    private RenewResult replayRenew(RenewalIdempotencyRecord rec, String owner, String envName) {
        if (!rec.owner().equals(owner) || !rec.envName().equals(envName)) {
            throw new ConflictException("续期幂等标识 " + rec.idempotencyId()
                    + " 已绑定 owner=" + rec.owner() + ", env=" + rec.envName());
        }
        if (!rec.accepted()) {
            log.info("op=RENEW_REPLAY idempotencyId={} env={} owner={} decision=REJECT_REPLAY reason={}",
                    rec.idempotencyId(), envName, owner, rec.rejectionCode());
            throw new RenewalRejectedException(
                    RenewalRejectedReason.valueOf(rec.rejectionCode()),
                    "幂等重放: " + rec.rejectionMessage());
        }
        log.info("op=RENEW_REPLAY idempotencyId={} env={} owner={} decision=ACCEPT_REPLAY basis=idempotentRecord",
                rec.idempotencyId(), envName, owner);
        return new RenewResult(rec.lease(), true, "幂等重放: " + rec.rejectionMessage());
    }

    private RenewResult rejectRenew(String idempotencyId, String owner, String envName,
                                    RenewalRejectedReason reason, String message) {
        RenewalIdempotencyRecord rec = new RenewalIdempotencyRecord(
                idempotencyId, owner, envName, false, null,
                reason.name(), message, reason.isRetryable(), clock.nowMillis());
        store.putRenewalIdempotencyIfAbsent(rec);
        log.info("op=RENEW idempotencyId={} env={} owner={} decision=REJECT reason={} retryable={} basis={}",
                idempotencyId, envName, owner, reason, reason.isRetryable(), message);
        throw new RenewalRejectedException(reason, message);
    }

    private void validateRenewArgs(String idempotencyId, String owner, String envName, long extendMillis) {
        if (idempotencyId == null || idempotencyId.isBlank()) {
            throw new IllegalArgumentException("idempotencyId 不能为空");
        }
        if (owner == null || owner.isBlank()) {
            throw new IllegalArgumentException("owner 不能为空");
        }
        if (envName == null || envName.isBlank()) {
            throw new IllegalArgumentException("envName 不能为空");
        }
        if (extendMillis <= 0) {
            throw new IllegalArgumentException("extendMillis 必须为正数");
        }
    }

    // ------------------------------------------------------------------
    // 回收
    // ------------------------------------------------------------------

    @Override
    public ReclaimResult reclaim(String envName) {
        if (envName == null || envName.isBlank()) {
            throw new IllegalArgumentException("envName 不能为空");
        }
        // 每环境互斥：把“重复/并发回收”收敛为同一条确定链路，避免并发重复执行回收动作。
        synchronized (envLock(envName)) {
            for (;;) {
                Lease lease = store.get(envName);
                if (lease == null) {
                    throw new ConflictException("环境 " + envName + " 不存在租约");
                }

                switch (lease.state()) {
                    case RECLAIMED -> {
                        log.info("op=RECLAIM env={} decision=REPLAY basis=alreadyReclaimed", envName);
                        return new ReclaimResult(envName, LeaseState.RECLAIMED, true, "已回收（重复/迟到回收）");
                    }
                    case RELEASED -> {
                        log.info("op=RECLAIM env={} decision=REPLAY basis=alreadyReleased", envName);
                        return new ReclaimResult(envName, LeaseState.RELEASED, true, "已释放（无需回收）");
                    }
                    case RELEASING -> throw new ConflictException(
                            "环境 " + envName + " 正在释放，请稍后重试");
                    case EXPIRED, RECLAIMING -> {
                        return doReclaim(envName);
                    }
                    case ACTIVE -> {
                        if (clock.nowMillis() < lease.expireAtMillis()) {
                            throw new ConflictException("环境 " + envName + " 租约尚未到期");
                        }
                        // 到期的 ACTIVE 先收敛到 EXPIRED（CAS），再进入回收。
                        Lease marked = store.compareAndUpdate(envName,
                                l -> l.state() == LeaseState.ACTIVE
                                        && clock.nowMillis() >= l.expireAtMillis(),
                                l -> l.withState(LeaseState.EXPIRED));
                        if (marked == null) {
                            continue;
                        }
                        return doReclaim(envName);
                    }
                }
            }
        }
    }

    /**
     * 执行一次回收尝试。进入时状态必须是 EXPIRED 或 RECLAIMING。
     * 失败时保留在 RECLAIMING（中间态、不回退、仍占配额直到真正回收），可重试。
     */
    private ReclaimResult doReclaim(String envName) {
        Lease claimed = store.compareAndUpdate(envName,
                l -> l.state() == LeaseState.EXPIRED || l.state() == LeaseState.RECLAIMING,
                l -> l.state() == LeaseState.RECLAIMING ? l : l.withState(LeaseState.RECLAIMING));
        if (claimed == null) {
            Lease now = store.get(envName);
            if (now != null && now.state() == LeaseState.RECLAIMED) {
                return new ReclaimResult(envName, LeaseState.RECLAIMED, true, "并发回收已完成");
            }
            throw new ConflictException("环境 " + envName + " 状态已变化，请重试");
        }

        try {
            simulator.attemptReclaim(envName);
        } catch (RuntimeException ex) {
            // 中途失败：状态停留在 RECLAIMING，配额暂不释放，等待重试回收。
            log.warn("op=RECLAIM env={} decision=FAILED_RETRYABLE basis={} state=RECLAIMING",
                    envName, ex.getMessage());
            throw new ReclaimFailureException("回收中途失败，环境 " + envName + " 停留在 RECLAIMING，可重试", ex);
        }

        Lease finished = store.compareAndUpdate(envName,
                l -> l.state() == LeaseState.RECLAIMING,
                l -> l.withState(LeaseState.RECLAIMED));
        if (finished == null) {
            Lease now = store.get(envName);
            log.info("op=RECLAIM env={} decision=REPLAY basis=concurrentReclaim state={}", envName,
                    now == null ? "null" : now.state());
            return new ReclaimResult(envName, now.state(), true, "并发回收已收敛: " + now.state());
        }
        log.info("op=RECLAIM env={} decision=RECLAIMED basis=reclaimActionSucceeded quotaReleased=true", envName);
        return new ReclaimResult(envName, LeaseState.RECLAIMED, false, "回收成功");
    }

    @Override
    public List<ReclaimResult> sweepExpired() {
        List<ReclaimResult> results = new ArrayList<>();
        long now = clock.nowMillis();
        List<String> candidates = store.list().stream()
                .filter(l -> (l.state() == LeaseState.ACTIVE && now >= l.expireAtMillis())
                        || l.state() == LeaseState.EXPIRED
                        || l.state() == LeaseState.RECLAIMING)
                .map(Lease::envName)
                .toList();
        for (String envName : candidates) {
            try {
                results.add(reclaim(envName));
            } catch (ReclaimFailureException ex) {
                // 扫描时遇到可恢复失败：保留中间态，记录但不阻断其他环境收敛。
                results.add(new ReclaimResult(envName, LeaseState.RECLAIMING, false,
                        "回收失败待重试: " + ex.getMessage()));
            }
        }
        return results;
    }
}
