package com.github.xingjianluo.ephemeralenvcontroljava.support;

/** 系统墙钟，生产环境使用。 */
public class SystemClock implements Clock {
    @Override
    public long nowMillis() {
        return System.currentTimeMillis();
    }
}
