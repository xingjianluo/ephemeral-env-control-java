package com.github.xingjianluo.ephemeralenvcontroljava.tests;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** REST 端到端冒烟：申请、幂等重放、配额查询、409 指纹冲突。 */
@SpringBootTest
@AutoConfigureMockMvc
class EnvControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void apply_idempotentReplay_andConflict() throws Exception {
        String body = """
                {"idempotencyKey":"web-1","holder":"alice","envId":"web-env","ttlMillis":2000}
                """;
        mockMvc.perform(post("/api/envs/apply").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("APPLY_SUCCESS"))
                .andExpect(jsonPath("$.lease.status").value("ACTIVE"));

        // 同键重放：同样结论
        mockMvc.perform(post("/api/envs/apply").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("APPLY_SUCCESS"));

        mockMvc.perform(get("/api/envs/quota"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.globalUsed").value(1));

        // 同键换持有者：409
        String conflict = """
                {"idempotencyKey":"web-1","holder":"bob","envId":"web-env"}
                """;
        mockMvc.perform(post("/api/envs/apply").contentType(MediaType.APPLICATION_JSON).content(conflict))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.decision").value("IDEMPOTENT_KEY_CONFLICT"));

        // 释放
        String release = """
                {"idempotencyKey":"web-rel-1","holder":"alice","envId":"web-env"}
                """;
        mockMvc.perform(post("/api/envs/release").contentType(MediaType.APPLICATION_JSON).content(release))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("RELEASE_SUCCESS"))
                .andExpect(jsonPath("$.lease.status").value("RELEASED"));
    }

    @Test
    void invalidRequest_returns400() throws Exception {
        String body = """
                {"idempotencyKey":"","holder":"alice","envId":"e"}
                """;
        mockMvc.perform(post("/api/envs/apply").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }
}
