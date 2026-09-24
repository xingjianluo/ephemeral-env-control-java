package com.github.xingjianluo.ephemeralenvcontroljava.support;

/** 可替换时钟，便于单测驱动租约到期，不依赖真实等待。 */
public interface Clock {
    long nowMillis();
}
