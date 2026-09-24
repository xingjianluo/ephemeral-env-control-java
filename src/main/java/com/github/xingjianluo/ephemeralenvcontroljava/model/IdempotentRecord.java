package com.github.xingjianluo.ephemeralenvcontroljava.model;

/**
 * 幂等记录：幂等标识 -> 首次确定结论。
 * 同一 key 重复提交且指纹一致时重放 result；指纹不一致直接冲突拒绝。
 */
public class IdempotentRecord {
    private final String idempotencyKey;
    private final OperationType operation;
    private final String holder;
    private final String envId;
    private final LeaseResult result;

    public IdempotentRecord(String idempotencyKey, OperationType operation, String holder,
                            String envId, LeaseResult result) {
        this.idempotencyKey = idempotencyKey;
        this.operation = operation;
        this.holder = holder;
        this.envId = envId;
        this.result = result;
    }

    public String getIdempotencyKey() { return idempotencyKey; }
    public OperationType getOperation() { return operation; }
    public String getHolder() { return holder; }
    public String getEnvId() { return envId; }
    public LeaseResult getResult() { return result; }
}
