package com.github.xingjianluo.ephemeralenvcontroljava.tests;

import com.github.xingjianluo.ephemeralenvcontroljava.service.EnvLeaseManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Spring 上下文冒烟：保证本地装配（时钟、回收器、管控服务、控制器）可启动。
 */
@SpringBootTest
public class ApplicationTests {

    @Autowired
    private EnvLeaseManager manager;

    @Test
    public void contentLoad() {
        assertNotNull(manager, "EnvLeaseManager 应被 Spring 装配");
    }
}
