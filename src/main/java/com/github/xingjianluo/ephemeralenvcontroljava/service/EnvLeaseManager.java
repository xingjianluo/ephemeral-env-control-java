package com.github.xingjianluo.ephemeralenvcontroljava.service;

import com.github.xingjianluo.ephemeralenvcontroljava.model.DecisionCode;
import com.github.xingjianluo.ephemeralenvcontroljava.model.IdempotentRecord;
import com.github.xingjianluo.ephemeralenvcontroljava.model.Lease;
import com.github.xingjianluo.ephemeralenvcontroljava.model.LeaseResult;
import com.github.xingjianluo.ephemeralenvcontroljava.model.LeaseStatus;
import com.github.xingjianluo.ephemeralenvcontroljava.model.OperationType;
import com.github.xingjianluo.ephemeralenvcontroljava.recycler.EnvRecycler;
import com.github.xingjianluo.ephemeralenvcontroljava.support.Clock;
import com.github.xingjianluo.ephemeralenvcontroljava.support.QuotaSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 临时环境生命周期管控核心服务（纯本地状态，进程内并发安全）。
 *
 * 职责：
 * 1) 申请/释放的幂等与争用控制（同一幂等键只生效一次、同键返回同结论、不同持有者不得互相覆盖）；
 * 2) 持有者 + 全局两级配额核算（超额可区分拒绝；释放/过期/回收失败不泄漏、不重复扣减）；
 * 3) 到期与异常链路收敛（重复/迟到/并发回收不回退状态；失败可重试，冲突与失败可区分）。
 *
 * 并发策略：单把状态锁保护所有状态迁移与配额计数；回收的外部动作在锁外执行，
 * 通过代际 generation 校验，迟到/旧代回调无法覆盖新状态。
 */
@Service
public class EnvLeaseManager {

    private static final Logger log = LoggerFactory.getLogger(EnvLeaseManager.class);

    private final int holderQuotaLimit;
    private final int globalQuotaLimit;
    private final long defaultTtlMillis;
    private final Clock clock;
    private final EnvRecycler recycler;

    /** envId -> 租约（RELEASED 也保留，用于换代重开与迟到回收判定） */
    private final Map<String, Lease> leases = new ConcurrentHashMap<>();
    /** 幂等标识 -> 首次结论记录 */
    private final Map<String, IdempotentRecord> idempotentStore = new ConcurrentHashMap<>();
    /** 持有者 -> 已占用配额（ACTIVE/EXPIRED/RELEASING 计数） */
    private final Map<String, Integer> holderUsed = new ConcurrentHashMap<>();
    /** 当前正在锁外执行回收动作的 envId：用于区分“在途并发回收”与“失败后重试” */
    private final java.util.Set<String> inFlightEnvIds = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private volatile int globalUsed = 0;

    private final ReentrantLock stateLock = new ReentrantLock();

    @org.springframework.beans.factory.annotation.Autowired
    public EnvLeaseManager(Clock clock, EnvRecycler recycler) {
        this(clock, recycler, 3, 5, 60_000L);
    }

    public EnvLeaseManager(Clock clock, EnvRecycler recycler,
                           int holderQuotaLimit, int globalQuotaLimit, long defaultTtlMillis) {
        this.clock = clock;
        this.recycler = recycler;
        this.holderQuotaLimit = holderQuotaLimit;
        this.globalQuotaLimit = globalQuotaLimit;
        this.defaultTtlMillis = defaultTtlMillis;
    }

    // ============================ 申请 ============================

    /**
     * 申请临时环境。
     * 同一 idempotencyKey 重复提交：指纹一致返回首次冻结结论；指纹冲突拒绝。
     */
    public LeaseResult apply(String idempotencyKey, String holder, String envId, Long ttlMillis) {
        requireText(idempotencyKey, "idempotencyKey");
        requireText(holder, "holder");
        requireText(envId, "envId");
        long ttl = ttlMillis == null ? defaultTtlMillis : ttlMillis;
        if (ttl <= 0) {
            throw new IllegalArgumentException("ttlMillis must be positive");
        }

        stateLock.lock();
        try {
            LeaseResult replay = checkReplay(idempotencyKey, OperationType.APPLY, holder, envId);
            if (replay != null) {
                return replay;
            }

            Lease lease = leases.get(envId);
            DecisionCode code;
            String detail;
            if (lease != null) {
                switch (lease.getStatus()) {
                    case ACTIVE, EXPIRED, RELEASING -> {
                        if (Objects.equals(lease.getHolder(), holder)) {
                            code = DecisionCode.ENV_OCCUPIED_BY_SELF;
                            detail = "env=" + envId + " 当前状态=" + lease.getStatus() + "，已属于本人";
                        } else {
                            code = DecisionCode.ENV_OCCUPIED_BY_OTHER;
                            detail = "env=" + envId + " 当前状态=" + lease.getStatus()
                                    + "，持有者=" + lease.getHolder();
                        }
                        return finish(idempotencyKey, OperationType.APPLY, holder, envId,
                                code, detail, lease, lease.getGeneration());
                    }
                    case RELEASED -> {
                        // 已回收终态：允许换代重开，继续走配额校验
                    }
                    default -> throw new IllegalStateException("unknown status");
                }
            }

            // 两级配额：先持有者维度，再全局维度，拒绝原因可区分
            int used = holderUsed.getOrDefault(holder, 0);
            if (used >= holderQuotaLimit) {
                return finish(idempotencyKey, OperationType.APPLY, holder, envId,
                        DecisionCode.HOLDER_QUOTA_EXCEEDED,
                        "holder=" + holder + " 已用=" + used + " 上限=" + holderQuotaLimit,
                        lease, lease == null ? 0L : lease.getGeneration());
            }
            if (globalUsed >= globalQuotaLimit) {
                return finish(idempotencyKey, OperationType.APPLY, holder, envId,
                        DecisionCode.GLOBAL_QUOTA_EXCEEDED,
                        "全局已用=" + globalUsed + " 上限=" + globalQuotaLimit,
                        lease, lease == null ? 0L : lease.getGeneration());
            }

            long now = clock.nowMillis();
            long expireAt = now + ttl;
            long generation;
            if (lease == null) {
                lease = new Lease(envId, holder, LeaseStatus.ACTIVE, now, expireAt, 0L);
                leases.put(envId, lease);
                generation = 0L;
            } else {
                lease.reopen(holder, now, expireAt);
                generation = lease.getGeneration();
            }
            holderUsed.merge(holder, 1, Integer::sum);
            globalUsed++;
            return finish(idempotencyKey, OperationType.APPLY, holder, envId,
                    DecisionCode.APPLY_SUCCESS,
                    "env=" + envId + " 租约生效 ttl=" + ttl + "ms 到期=" + expireAt
                            + " 持有者配额=" + holderUsed.get(holder) + "/" + holderQuotaLimit
                            + " 全局配额=" + globalUsed + "/" + globalQuotaLimit,
                    lease, generation);
        } finally {
            stateLock.unlock();
        }
    }

    // ============================ 释放/回收 ============================

    /** 持有者主动释放：内部按 ACTIVE->RELEASING->回收 的确定链路处理。 */
    public LeaseResult release(String idempotencyKey, String holder, String envId) {
        requireText(idempotencyKey, "idempotencyKey");
        requireText(holder, "holder");
        requireText(envId, "envId");
        return doRecycle(idempotencyKey, holder, envId, OperationType.RELEASE);
    }

    /** 过期巡检/手动重试触发回收（系统身份，holder 记为 null）。 */
    public LeaseResult expireRecycle(String idempotencyKey, String envId) {
        requireText(idempotencyKey, "idempotencyKey");
        requireText(envId, "envId");
        return doRecycle(idempotencyKey, null, envId, OperationType.EXPIRE_RECYCLE);
    }

    /** 回收在途失败后的重试。 */
    public LeaseResult retryRecycle(String idempotencyKey, String envId) {
        requireText(idempotencyKey, "idempotencyKey");
        requireText(envId, "envId");
        return doRecycle(idempotencyKey, null, envId, OperationType.RETRY_RECYCLE);
    }

    private LeaseResult doRecycle(String idempotencyKey, String holder, String envId, OperationType opType) {
        stateLock.lock();
        long generation;
        try {
            LeaseResult replay = checkReplay(idempotencyKey, opType, holder, envId);
            if (replay != null) {
                return replay;
            }

            Lease lease = leases.get(envId);
            if (lease == null) {
                return finish(idempotencyKey, opType, holder, envId,
                        DecisionCode.LEASE_NOT_FOUND, "env=" + envId + " 从未申请", null, 0L);
            }

            // 主动释放必须是持有者本人（系统回收 holder 为 null，跳过此校验）
            if (holder != null && lease.getHolder() != null
                    && !Objects.equals(lease.getHolder(), holder)) {
                return finish(idempotencyKey, opType, holder, envId,
                        DecisionCode.NOT_LEASE_OWNER,
                        "env=" + envId + " 持有者=" + lease.getHolder() + " 操作者=" + holder,
                        lease, lease.getGeneration());
            }

            // 惰性到期：ACTIVE 且到期 -> EXPIRED
            if (lease.getStatus() == LeaseStatus.ACTIVE && clock.nowMillis() >= lease.getExpireAt()) {
                lease.markExpired();
                log.info("idempotencyKey={} op={} env={} basis=惰性到期 ACTIVE->EXPIRED 到期={} now={}",
                        idempotencyKey, opType, envId, lease.getExpireAt(), clock.nowMillis());
            }

            // 并发回收防护：进入临界区时若已有另一次回收在锁外执行，拒绝重复发起。
            // 注意：能走到这里且使用新幂等键，通常意味着上次回收已失败收尾（RELEASING 是失败收敛态），
            // 那种情况由下方“重入窗口”放行重试；只有真正在途的并发回收才在此冲突。
            boolean inFlight = inFlightEnvIds.contains(envId);

            switch (lease.getStatus()) {
                case ACTIVE, EXPIRED -> lease.markReleasing();
                case RELEASING -> {
                    if (inFlight) {
                        return finish(idempotencyKey, opType, holder, envId,
                                DecisionCode.ALREADY_RELEASING,
                                "env=" + envId + " 回收在途 generation=" + lease.getGeneration()
                                        + "，拒绝并发重复发起，可稍后用新幂等键重试确认",
                                lease, lease.getGeneration());
                    }
                    // 重入窗口：上次回收已失败收尾，本次为合法重试，状态保持 RELEASING、代际不变。
                    log.info("idempotencyKey={} op={} env={} basis=失败收敛态重试 保持RELEASING generation={}",
                            idempotencyKey, opType, envId, lease.getGeneration());
                    lease.stayReleasing();
                }
                case RELEASED -> {
                    return finish(idempotencyKey, opType, holder, envId,
                            DecisionCode.ALREADY_RELEASED,
                            "env=" + envId + " 已于上代回收完成 generation=" + lease.getGeneration()
                                    + "，重复回收不回退状态",
                            lease, lease.getGeneration());
                }
                default -> throw new IllegalStateException("unknown status");
            }
            inFlightEnvIds.add(envId);
            generation = lease.getGeneration();
        } finally {
            stateLock.unlock();
        }

        // 外部回收动作在锁外执行（模拟真实 IO，可能失败）
        boolean success = invokeRecycler(envId, generation);
        return completeRecycle(idempotencyKey, holder, envId, generation, opType, success);
    }

    /**
     * 回收收尾：根据外部动作成败与代际是否匹配做确定收敛。
     * 包可见，作为“迟到回收”场景的受控验证入口（模拟回调晚于换代重开返回）。
     *
     * @param success 外部回收动作结果
     * @return 确定结论；旧代际的迟到结果一律 LATE_RECYCLE_IGNORED，当前状态不回退
     */
    public LeaseResult completeRecycle(String idempotencyKey, String holder, String envId,
                                long generation, OperationType opType, boolean success) {
        stateLock.lock();
        try {
            Lease lease = leases.get(envId);
            if (lease == null) {
                return finish(idempotencyKey, opType, holder, envId,
                        DecisionCode.LEASE_NOT_FOUND,
                        "env=" + envId + " 回收收尾时租约已不存在", null, generation);
            }
            // 迟到回收防护：代际或状态不匹配，说明已换代，旧结果必须忽略
            if (lease.getGeneration() != generation || lease.getStatus() != LeaseStatus.RELEASING) {
                return finish(idempotencyKey, opType, holder, envId,
                        DecisionCode.LATE_RECYCLE_IGNORED,
                        "env=" + envId + " 迟到回调代际=" + generation + " 当前代际=" + lease.getGeneration()
                                + " 当前状态=" + lease.getStatus() + "，忽略旧结果，状态不回退",
                        lease, lease.getGeneration());
            }
            if (success) {
                String oldHolder = lease.getHolder();
                lease.markReleased();
                if (oldHolder != null) {
                    decrementHolder(oldHolder);
                }
                globalUsed--;
                if (globalUsed < 0) {
                    throw new IllegalStateException("globalUsed negative for env=" + envId);
                }
                DecisionCode code = opType == OperationType.RELEASE
                        ? DecisionCode.RELEASE_SUCCESS : DecisionCode.RECYCLE_SUCCESS;
                return finish(idempotencyKey, opType, holder, envId, code,
                        "env=" + envId + " 代际=" + generation + " RELEASING->RELEASED 配额已归还"
                                + " 全局已用=" + globalUsed + "/" + globalQuotaLimit,
                        lease, lease.getGeneration());
            }
            // 失败：状态收敛保持 RELEASING，配额不归还（避免泄漏与重复扣减），可安全重试
            lease.stayReleasing();
            return finish(idempotencyKey, opType, holder, envId,
                    DecisionCode.RECYCLE_FAILED,
                    "env=" + envId + " 代际=" + generation + " 保持RELEASING 配额继续占用，可重试",
                    lease, generation);
        } finally {
            inFlightEnvIds.remove(envId);
            stateLock.unlock();
        }
    }

    private boolean invokeRecycler(String envId, long generation) {
        try {
            recycler.recycle(envId, generation);
            return true;
        } catch (Exception e) {
            log.warn("env={} generation={} 回收外部动作异常: {}", envId, generation, e.toString());
            return false;
        }
    }

    // ============================ 过期巡检 ============================

    /** 把所有已到期的 ACTIVE 租约标记为 EXPIRED（不回收，配额仍占用），返回受影响 envId。 */
    public List<String> sweepExpired() {
        long now = clock.nowMillis();
        List<String> expired = new ArrayList<>();
        stateLock.lock();
        try {
            for (Lease lease : leases.values()) {
                if (lease.getStatus() == LeaseStatus.ACTIVE && now >= lease.getExpireAt()) {
                    lease.markExpired();
                    expired.add(lease.getEnvId());
                    log.info("op=EXPIRE_SWEEP env={} basis=now={} >= 到期={} ACTIVE->EXPIRED 配额仍占用",
                            lease.getEnvId(), now, lease.getExpireAt());
                }
            }
            return expired;
        } finally {
            stateLock.unlock();
        }
    }

    // ============================ 查询与审计 ============================

    /** 查询租约只读快照，不存在返回 null。 */
    public Lease getLease(String envId) {
        Lease lease = leases.get(envId);
        return lease == null ? null : lease.copy();
    }

    /** 配额与租约计数快照。 */
    public QuotaSnapshot quotaSnapshot() {
        stateLock.lock();
        try {
            int count = 0;
            for (Lease lease : leases.values()) {
                if (lease.occupiesQuota()) {
                    count++;
                }
            }
            return new QuotaSnapshot(new LinkedHashMap<>(holderUsed), globalUsed,
                    holderQuotaLimit, globalQuotaLimit, count);
        } finally {
            stateLock.unlock();
        }
    }

    /**
     * 守恒审计：按租约实际状态重算配额，与计数器逐项比对。
     * @return true 表示 持有者明细一致 且 持有者计数之和 == 全局计数 == 占用态租约数
     */
    public boolean auditConservation() {
        stateLock.lock();
        try {
            Map<String, Integer> recompute = new LinkedHashMap<>();
            int count = 0;
            for (Lease lease : leases.values()) {
                if (lease.occupiesQuota()) {
                    count++;
                    String h = lease.getHolder();
                    if (h == null) {
                        log.warn("守恒审计失败: env={} 占用态但无持有者", lease.getEnvId());
                        return false;
                    }
                    recompute.merge(h, 1, Integer::sum);
                }
            }
            if (count != globalUsed) {
                log.warn("守恒审计失败: 占用租约数={} 全局计数={}", count, globalUsed);
                return false;
            }
            if (!recompute.equals(holderUsed)) {
                log.warn("守恒审计失败: 重算持有者明细={} 计数器={}", recompute, holderUsed);
                return false;
            }
            int sum = holderUsed.values().stream().mapToInt(Integer::intValue).sum();
            if (sum != globalUsed) {
                log.warn("守恒审计失败: 持有者计数之和={} 全局计数={}", sum, globalUsed);
                return false;
            }
            log.info("守恒审计通过: 占用租约数={} 全局计数={} 持有者明细={}", count, globalUsed, recompute);
            return true;
        } finally {
            stateLock.unlock();
        }
    }

    // ============================ 内部工具 ============================

    private void decrementHolder(String holder) {
        Integer v = holderUsed.get(holder);
        if (v == null || v <= 0) {
            throw new IllegalStateException("holder quota underflow: " + holder);
        }
        if (v == 1) {
            holderUsed.remove(holder);
        } else {
            holderUsed.put(holder, v - 1);
        }
    }

    /**
     * 幂等校验（状态锁内调用）。
     * @return 非 null 表示应直接重放该冻结结论；null 表示首次提交
     */
    private LeaseResult checkReplay(String key, OperationType op, String holder, String envId) {
        IdempotentRecord rec = idempotentStore.get(key);
        if (rec == null) {
            return null;
        }
        if (rec.getOperation() == op
                && Objects.equals(rec.getHolder(), holder)
                && Objects.equals(rec.getEnvId(), envId)) {
            LeaseResult first = rec.getResult();
            log.info("idempotencyKey={} op={} env={} basis=幂等重放 首次决策={} 返回冻结结论",
                    key, op, envId, first.getCode());
            return first;
        }
        log.info("idempotencyKey={} op={} env={} basis=幂等键指纹冲突 已绑定 op={} holder={} env={}",
                key, op, envId, rec.getOperation(), rec.getHolder(), rec.getEnvId());
        throw new IdempotentConflictException(key,
                "幂等键 " + key + " 已绑定 op=" + rec.getOperation()
                        + " holder=" + rec.getHolder() + " env=" + rec.getEnvId());
    }

    /** 构造冻结结论、记录幂等并打印判定日志（状态锁内调用）。 */
    private LeaseResult finish(String key, OperationType op, String holder, String envId,
                               DecisionCode code, String detail, Lease lease, long generation) {
        Lease snapshot = lease == null ? null : lease.copy();
        LeaseResult result = new LeaseResult(key, op, code, detail, snapshot, generation);
        idempotentStore.put(key, new IdempotentRecord(key, op, holder, envId, result));
        log.info("idempotencyKey={} op={} env={} holder={} decision={} success={} retryable={} conflict={} basis={}",
                key, op, envId, holder, code, code.isSuccess(), code.isRetryable(),
                code.isConflict(), detail);
        return result;
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
