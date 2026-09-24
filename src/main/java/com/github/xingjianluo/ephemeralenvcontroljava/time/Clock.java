package com.github.xingjianluo.ephemeralenvcontroljava.time;

/**
 * 可替换时钟，便于测试过期逻辑。
 */
public interface Clock {
    long nowMillis();
}
