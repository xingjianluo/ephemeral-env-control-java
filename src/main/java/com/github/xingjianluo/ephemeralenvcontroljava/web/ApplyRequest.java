package com.github.xingjianluo.ephemeralenvcontroljava.web;

/** 申请请求。idempotencyKey 与 holder 必填。 */
public class ApplyRequest {
    private String idempotencyKey;
    private String holder;
    private String envId;
    private Long ttlMillis;

    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public String getHolder() { return holder; }
    public void setHolder(String holder) { this.holder = holder; }
    public String getEnvId() { return envId; }
    public void setEnvId(String envId) { this.envId = envId; }
    public Long getTtlMillis() { return ttlMillis; }
    public void setTtlMillis(Long ttlMillis) { this.ttlMillis = ttlMillis; }
}
