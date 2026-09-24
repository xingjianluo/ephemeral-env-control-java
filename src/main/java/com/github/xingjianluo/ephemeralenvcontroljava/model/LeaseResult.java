package com.github.xingjianluo.ephemeralenvcontroljava.model;

/**
 * 每次管控操作的确定结论：决策码 + 判定依据 + 租约快照。
 */
public class LeaseResult {
    private final String idempotencyKey;
    private final OperationType operation;
    private final DecisionCode code;
    private final String reasonDetail;
    private final Lease leaseSnapshot;
    private final long generationAtDecision;

    public LeaseResult(String idempotencyKey, OperationType operation, DecisionCode code,
                       String reasonDetail, Lease leaseSnapshot, long generationAtDecision) {
        this.idempotencyKey = idempotencyKey;
        this.operation = operation;
        this.code = code;
        this.reasonDetail = reasonDetail;
        this.leaseSnapshot = leaseSnapshot;
        this.generationAtDecision = generationAtDecision;
    }

    public String getIdempotencyKey() { return idempotencyKey; }
    public OperationType getOperation() { return operation; }
    public DecisionCode getCode() { return code; }
    public String getReasonDetail() { return reasonDetail; }
    public Lease getLeaseSnapshot() { return leaseSnapshot; }
    public long getGenerationAtDecision() { return generationAtDecision; }
    public boolean isSuccess() { return code.isSuccess(); }

    /** 幂等重放时使用：保持结论不变，只替换为当时的快照。 */
    public LeaseResult withSnapshot(Lease snapshot) {
        return new LeaseResult(idempotencyKey, operation, code, reasonDetail, snapshot, generationAtDecision);
    }
}
