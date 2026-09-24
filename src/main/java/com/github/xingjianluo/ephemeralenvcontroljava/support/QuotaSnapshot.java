package com.github.xingjianluo.ephemeralenvcontroljava.support;

import java.util.Map;

/**
 * 配额快照：持有者配额使用明细 + 全局使用量。
 * 仅 ACTIVE/EXPIRED/RELEASING 状态的租约占用配额；RELEASED 不占用。
 */
public record QuotaSnapshot(Map<String, Integer> usedByHolder,
                            int globalUsed,
                            int holderLimit,
                            int globalLimit,
                            int leaseCount) {
}
