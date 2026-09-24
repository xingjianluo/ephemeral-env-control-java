package com.github.xingjianluo.ephemeralenvcontroljava.web;

/** 释放/回收请求。回收接口 holder 可空（巡检身份）。 */
public class OperationRequest {
    private String idempotencyKey;
    private String holder;
    private String envId;

    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public String getHolder() { return holder; }
    public void setHolder(String holder) { this.holder = holder; }
    public String getEnvId() { return envId; }
    public void setEnvId(String envId) { this.envId = envId; }
}
