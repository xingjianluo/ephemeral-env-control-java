package com.github.xingjianluo.ephemeralenvcontroljava.service;

import com.github.xingjianluo.ephemeralenvcontroljava.recycler.EnvRecycler;
import com.github.xingjianluo.ephemeralenvcontroljava.recycler.SimulatedRecycler;
import com.github.xingjianluo.ephemeralenvcontroljava.support.Clock;
import com.github.xingjianluo.ephemeralenvcontroljava.support.SystemClock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 本地装配：时钟与回收器均为进程内实现，不依赖任何外部服务。 */
@Configuration
public class LocalConfig {

    @Bean
    public Clock clock() {
        return new SystemClock();
    }

    @Bean
    public EnvRecycler envRecycler() {
        return new SimulatedRecycler();
    }
}
