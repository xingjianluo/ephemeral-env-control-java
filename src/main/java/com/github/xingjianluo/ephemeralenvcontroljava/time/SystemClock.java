package com.github.xingjianluo.ephemeralenvcontroljava.time;

import org.springframework.stereotype.Component;

@Component
public class SystemClock implements Clock {
    @Override
    public long nowMillis() {
        return System.currentTimeMillis();
    }
}
