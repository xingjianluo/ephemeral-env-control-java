package com.github.xingjianluo.ephemeralenvcontroljava.store;

import com.github.xingjianluo.ephemeralenvcontroljava.domain.Lease;
import com.github.xingjianluo.ephemeralenvcontroljava.domain.LeaseState;

import java.util.List;
import java.util.function.UnaryOperator;

/**
 * 租约与幂等记录的本地存储。所有变更均为原子操作。
 */
public interface LeaseStore {

    Lease get(String envName);

    List<Lease> list();

    /**
     * 以 CAS 方式原子更新某环境的租约；expect 描述当前状态断言。
     * 返回更新后的租约；若断言不满足返回 null。
     */
    Lease compareAndUpdate(String envName, java.util.function.Predicate<Lease> expect, UnaryOperator<Lease> update);

    /** 若该环境不存在任何租约，则原子创建；否则返回当前值且 created=false。 */
    CreatedOrExisting createIfAbsent(Lease lease);

    IdempotencyRecord getIdempotency(String idempotencyId);

    void putIdempotencyIfAbsent(IdempotencyRecord record);

    /** 状态统计（含各状态租约数），用于测试断言守恒。 */
    long countByState(LeaseState state);

    void clear();

    record CreatedOrExisting(Lease lease, boolean created) { }
}
