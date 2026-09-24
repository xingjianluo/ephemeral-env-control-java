package com.github.xingjianluo.ephemeralenvcontroljava.model;

/**
 * 临时环境租约实体（本地状态模拟）。
 * generation 在每次成功回收后 +1，用于识别迟到回收，防止旧代结果回退新代状态。
 * 所有迁移方法仅限同包的管控服务在持有状态锁时调用。
 */
public class Lease {
    private final String envId;
    private String holder;
    private LeaseStatus status;
    private long acquiredAt;
    private long expireAt;
    private long generation;

    public Lease(String envId, String holder, LeaseStatus status,
                 long acquiredAt, long expireAt, long generation) {
        this.envId = envId;
        this.holder = holder;
        this.status = status;
        this.acquiredAt = acquiredAt;
        this.expireAt = expireAt;
        this.generation = generation;
    }

    public String getEnvId() { return envId; }
    public String getHolder() { return holder; }
    public LeaseStatus getStatus() { return status; }
    public long getAcquiredAt() { return acquiredAt; }
    public long getExpireAt() { return expireAt; }
    public long getGeneration() { return generation; }

    /** 到期：ACTIVE -> EXPIRED（配额仍占用）。 */
    public void markExpired() {
        if (status != LeaseStatus.ACTIVE) {
            throw new IllegalStateException("only ACTIVE lease can expire: " + envId);
        }
        status = LeaseStatus.EXPIRED;
    }

    /** 进入回收在途：ACTIVE(先到期)/EXPIRED -> RELEASING。 */
    public void markReleasing() {
        if (status != LeaseStatus.EXPIRED && status != LeaseStatus.ACTIVE) {
            throw new IllegalStateException("only ACTIVE/EXPIRED lease can start recycling: " + envId);
        }
        status = LeaseStatus.RELEASING;
    }

    /**
     * 回收完成：-> RELEASED，代际 +1，清空持有者。配额由服务层归还。
     * @return 完成时的旧代际（供外部回调比对）
     */
    public long markReleased() {
        if (status != LeaseStatus.RELEASING) {
            throw new IllegalStateException("only RELEASING lease can be released: " + envId);
        }
        status = LeaseStatus.RELEASED;
        holder = null;
        long old = generation;
        generation = generation + 1;
        return old;
    }

    /** 回收失败后保持/回到 RELEASING（仅允许从 RELEASING 收敛）。 */
    public void stayReleasing() {
        status = LeaseStatus.RELEASING;
    }

    /** 回收失败后若发生代际切换，不允许回退，该方法仅做显式断言。 */
    boolean isReleasing() {
        return status == LeaseStatus.RELEASING;
    }

    /** 重新申请（仅 RELEASED 可换代重开）：-> ACTIVE。 */
    public void reopen(String newHolder, long now, long expireAtMillis) {
        if (status != LeaseStatus.RELEASED) {
            throw new IllegalStateException("only RELEASED env can be re-applied: " + envId);
        }
        holder = newHolder;
        status = LeaseStatus.ACTIVE;
        acquiredAt = now;
        expireAt = expireAtMillis;
    }

    public boolean occupiesQuota() {
        return status == LeaseStatus.ACTIVE
                || status == LeaseStatus.EXPIRED
                || status == LeaseStatus.RELEASING;
    }

    /** 防御性快照拷贝：对外只读，避免外部修改内部状态。 */
    public Lease copy() {
        return new Lease(envId, holder, status, acquiredAt, expireAt, generation);
    }
}
