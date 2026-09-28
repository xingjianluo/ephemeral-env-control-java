package com.github.xingjianluo.ephemeralenvcontroljava.store;

import com.github.xingjianluo.ephemeralenvcontroljava.domain.Lease;
import com.github.xingjianluo.ephemeralenvcontroljava.domain.LeaseState;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * 本地内存存储：
 * - 每个 key 的 compute 是原子的，CAS 语义通过“状态断言 + compute”实现；
 * - 幂等记录用 putIfAbsent 保证首次结论唯一。
 */
@Component
public class InMemoryLeaseStore implements LeaseStore {

    private final ConcurrentHashMap<String, Lease> leases = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, IdempotencyRecord> idempotency = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, RenewalIdempotencyRecord> renewalIdempotency = new ConcurrentHashMap<>();

    @Override
    public Lease get(String envName) {
        return leases.get(envName);
    }

    @Override
    public List<Lease> list() {
        return new ArrayList<>(leases.values());
    }

    @Override
    public Lease compareAndUpdate(String envName, Predicate<Lease> expect, UnaryOperator<Lease> update) {
        AtomicReference<Lease> outcome = new AtomicReference<>();
        leases.compute(envName, (k, current) -> {
            if (!expect.test(current)) {
                outcome.set(null);
                return current;
            }
            Lease next = update.apply(current);
            outcome.set(next);
            return next;
        });
        return outcome.get();
    }

    @Override
    public CreatedOrExisting createIfAbsent(Lease lease) {
        AtomicBoolean created = new AtomicBoolean(false);
        Lease existing = leases.compute(lease.envName(), (k, current) -> {
            if (current != null) {
                return current;
            }
            created.set(true);
            return lease;
        });
        return new CreatedOrExisting(existing, created.get());
    }

    @Override
    public IdempotencyRecord getIdempotency(String idempotencyId) {
        return idempotency.get(idempotencyId);
    }

    @Override
    public void putIdempotencyIfAbsent(IdempotencyRecord record) {
        idempotency.putIfAbsent(record.idempotencyId(), record);
    }

    @Override
    public RenewalIdempotencyRecord getRenewalIdempotency(String idempotencyId) {
        return renewalIdempotency.get(idempotencyId);
    }

    @Override
    public void putRenewalIdempotencyIfAbsent(RenewalIdempotencyRecord record) {
        renewalIdempotency.putIfAbsent(record.idempotencyId(), record);
    }

    @Override
    public long countByState(LeaseState state) {
        return leases.values().stream().filter(l -> l.state() == state).count();
    }

    @Override
    public void clear() {
        leases.clear();
        idempotency.clear();
        renewalIdempotency.clear();
    }
}
