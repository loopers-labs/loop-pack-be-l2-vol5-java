package com.loopers.common.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 시작 로그를 보려면 이 클래스에서 컨텍스트가 새로 떠야 하므로 시작 전에 캐시된 컨텍스트를 버린다.
// 비밀번호 값은 이 테스트 전용이며 운영 계정과 무관하다.
@ExtendWith(OutputCaptureExtension.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
@SpringBootTest(properties = {
    "commerce.admin.basic-auth.enabled=true",
    "commerce.admin.basic-auth.username=ops-admin",
    "commerce.admin.basic-auth.password=test-only-admin-pass"})
@AutoConfigureMockMvc
class AdminSecurityStartupLogTest {
    @Autowired private MockMvc mvc;

    @Test
    @DisplayName("관리자 Basic을 켜고 시작해도 Boot 기본 사용자의 생성 비밀번호 WARN을 남기지 않고 관리자 Basic은 그대로 동작한다")
    void startsWithoutGeneratedPasswordWarning(CapturedOutput output) throws Exception {
        assertThat(output.getAll()).as("시작 로그를 잡았는지").contains("Started AdminSecurityStartupLogTest");
        assertThat(output.getAll()).doesNotContain("Using generated security password");

        mvc.perform(get("/api-admin/v1/orders").with(httpBasic("ops-admin", "test-only-admin-pass")))
            .andExpect(status().isOk());
        mvc.perform(get("/api-admin/v1/orders").with(httpBasic("ops-admin", "wrong-pass")))
            .andExpect(status().isUnauthorized());
        mvc.perform(get("/api-admin/v1/orders")).andExpect(status().isForbidden());
    }
}
