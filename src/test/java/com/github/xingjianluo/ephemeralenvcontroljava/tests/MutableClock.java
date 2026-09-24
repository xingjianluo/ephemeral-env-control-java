package com.github.xingjianluo.ephemeralenvcontroljava.tests;

import com.github.xingjianluo.ephemeralenvcontroljava.support.Clock;

/** 测试用可控时钟：手动推进时间以驱动租约到期，无需真实等待。 */
public class MutableClock implements Clock {
    private long now;

    public MutableClock() {
        this(1_000_000L);
    }

    public MutableClock(long start) {
        this.now = start;
    }

    @Override
    public long nowMillis() {
        return now;
    }

    public void advanceMillis(long delta) {
        this.now += delta;
    }
}
