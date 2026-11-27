package com.loopers.interfaces.api.pg;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@ExtendWith(OutputCaptureExtension.class)
class PgAccountFilterTest {
    private final PgAccountFilter filter = new PgAccountFilter();

    @Test
    @DisplayName("승인 생성 요청은 계정 거절 여부와 무관하게 진입 시 한 번만 기록하고 조회 요청은 제외한다")
    void recordsCreatesAtIngress(CapturedOutput output) throws Exception {
        var create = request("POST", "/api/v1/payments", "135135");
        create.setContent("private-payment-body".getBytes(StandardCharsets.UTF_8));
        var accepted = new MockHttpServletResponse();
        filter.doFilter(create, accepted, (request, response) -> assertThat(createLogs(output)).isEqualTo(1));
        assertThat(accepted.getStatus()).isEqualTo(200);

        var rejected = new MockHttpServletResponse();
        filter.doFilter(request("POST", "/api/v1/payments", "private-account"), rejected, new MockFilterChain());
        assertThat(rejected.getStatus()).isEqualTo(403);
        assertThat(createLogs(output)).isEqualTo(2);

        filter.doFilter(request("GET", "/api/v1/payments", "135135"), new MockHttpServletResponse(), new MockFilterChain());
        filter.doFilter(request("POST", "/api/v1/payments/callback", "135135"), new MockHttpServletResponse(), new MockFilterChain());
        assertThat(createLogs(output)).isEqualTo(2);
        assertThat(output.getAll()).doesNotContain("private-payment-body", "private-account", "X-USER-ID", "135135");
    }

    private static MockHttpServletRequest request(String method, String path, String account) {
        var request = new MockHttpServletRequest(method, "/test-pg" + path);
        request.setContextPath("/test-pg");
        request.setServletPath(path);
        request.addHeader("X-USER-ID", account);
        return request;
    }

    private static long createLogs(CapturedOutput output) {
        return output.getAll().lines().filter(line -> line.contains("operation=PG_CREATE_RECEIVED")).count();
    }
}
