package com.github.xingjianluo.ephemeralenvcontroljava.quota;

import com.github.xingjianluo.ephemeralenvcontroljava.domain.LeaseState;
import com.github.xingjianluo.ephemeralenvcontroljava.store.LeaseStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Set;

/**
 * 两级配额核算：持有者维度 + 全局维度。
 *
 * 设计要点：配额不使用独立计数器增减，而是在每次判定时从租约存储实时统计
 * “占用中”状态的租约数量。这样释放、过期、回收的任何状态流转都只会让一条
 * 租约在“占用 / 不占用”之间切换一次，从根上杜绝配额泄漏与重复扣减。
 *
 * 占用态：ACTIVE / EXPIRED / RELEASING / RECLAIMING（资源尚未真正回收）。
 * 非占用态（终态）：RELEASED / RECLAIMED。
 */
@Component
public class QuotaService {

    public static final Set<LeaseState> OCCUPYING_STATES =
            EnumSet.of(LeaseState.ACTIVE, LeaseState.EXPIRED,
                       LeaseState.RELEASING, LeaseState.RECLAIMING);

    private final LeaseStore store;
    private final int perOwnerLimit;
    private final int globalLimit;

    public QuotaService(LeaseStore store,
                        @Value("${ephemeral.quota.per-owner:2}") int perOwnerLimit,
                        @Value("${ephemeral.quota.global:4}") int globalLimit) {
        this.store = store;
        this.perOwnerLimit = perOwnerLimit;
        this.globalLimit = globalLimit;
    }

    public int getPerOwnerLimit() {
        return perOwnerLimit;
    }

    public int getGlobalLimit() {
        return globalLimit;
    }

    public boolean isOccupying(LeaseState state) {
        return OCCUPYING_STATES.contains(state);
    }

    /** 指定持有者当前占用数。 */
    public long countByOwner(String owner) {
        return store.list().stream()
                .filter(l -> owner.equals(l.owner()) && isOccupying(l.state()))
                .count();
    }

    /** 全局当前占用数。 */
    public long countGlobal() {
        return store.list().stream()
                .filter(l -> isOccupying(l.state()))
                .count();
    }
}
