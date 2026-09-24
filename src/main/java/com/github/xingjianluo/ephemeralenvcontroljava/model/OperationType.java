package com.github.xingjianluo.ephemeralenvcontroljava.model;

/** 管控操作类型，用于日志与幂等记录。 */
public enum OperationType {
    /** 申请临时环境 */
    APPLY,
    /** 持有者主动释放 */
    RELEASE,
    /** 过期巡检触发的回收 */
    EXPIRE_RECYCLE,
    /** 失败回收的重试 */
    RETRY_RECYCLE
}
