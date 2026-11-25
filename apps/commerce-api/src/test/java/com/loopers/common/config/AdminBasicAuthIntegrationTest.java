package com.loopers.common.config;

import com.loopers.domains.brand.app.BrandApplicationService;
import com.loopers.domains.product.app.ProductApplicationService;
import com.loopers.domains.user.out.UserJpaEntity;
import com.loopers.domains.user.out.UserJpaRepository;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 관리자 HTTP Basic은 local·dev 프로필 설정이 켜고 계정은 환경 변수로만 받는다. test 프로필에서는 같은 프로퍼티를 직접 넣어 증명한다.
// 비밀번호 값은 이 테스트 전용이며 운영 계정과 무관하다.
@SpringBootTest
@AutoConfigureMockMvc
class AdminBasicAuthIntegrationTest {
    private static final String ADMIN_LIST = "/api-admin/v1/orders";
    private static final String ADMIN_CREATE_BRAND = "/api-admin/v1/brands";
    private static final String USERNAME = "ops-admin";
    private static final String PASSWORD = "test-only-admin-pass";

    @Autowired private MockMvc mvc;
    @Autowired private BrandApplicationService brands;
    @Autowired private ProductApplicationService products;
    @Autowired private UserJpaRepository users;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DatabaseCleanUp cleanup;

    @BeforeEach
    void prepare() {
        users.save(new UserJpaEntity(1));
    }

    @AfterEach
    void clean() {
        jdbc.update("delete from order_items");
        cleanup.truncateAllTables();
    }

    private long brandCount() {
        return jdbc.queryForObject("select count(*) from brands", Long.class);
    }

    @Nested
    @TestPropertySource(properties = {
        "commerce.admin.basic-auth.username=" + USERNAME,
        "commerce.admin.basic-auth.password=" + PASSWORD})
    class BasicAuthNotEnabled {
        @Test
        @DisplayName("Basic 인증을 켜지 않은 프로필은 계정 값이 있어도 맞는 Basic 요청을 지금처럼 403으로 거절한다")
        void rejectsEvenMatchingBasic() throws Exception {
            mvc.perform(get(ADMIN_LIST).with(httpBasic(USERNAME, PASSWORD))).andExpect(status().isForbidden());
            mvc.perform(get(ADMIN_LIST)).andExpect(status().isForbidden());
        }
    }

    @Nested
    @TestPropertySource(properties = "commerce.admin.basic-auth.enabled=true")
    class BasicAuthEnabledWithoutAccount {
        @Test
        @DisplayName("Basic 인증을 켰지만 계정 환경 변수가 없으면 어떤 Basic 요청도 관리자 API에서 지금처럼 403으로 거절한다")
        void rejectsAllAdminRequests() throws Exception {
            mvc.perform(get(ADMIN_LIST).with(httpBasic(USERNAME, PASSWORD))).andExpect(status().isForbidden());
            mvc.perform(get(ADMIN_LIST).with(httpBasic("", ""))).andExpect(status().isForbidden());
            mvc.perform(post(ADMIN_CREATE_BRAND).with(csrf()).with(httpBasic(USERNAME, PASSWORD))
                    .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"브랜드\"}"))
                .andExpect(status().isForbidden());
            assertThat(brandCount()).isZero();
        }
    }

    @Nested
    @TestPropertySource(properties = {
        "commerce.admin.basic-auth.enabled=true",
        "commerce.admin.basic-auth.username=" + USERNAME,
        "commerce.admin.basic-auth.password=" + PASSWORD})
    class BasicAuthEnabledWithAccount {
        @Test
        @DisplayName("Basic 인증을 켜고 계정을 설정하면 맞는 Basic 관리자 조회는 200이다")
        void acceptsMatchingBasicOnRead() throws Exception {
            mvc.perform(get(ADMIN_LIST).with(httpBasic(USERNAME, PASSWORD))).andExpect(status().isOk());
        }

        @Test
        @DisplayName("Basic 인증을 켜고 계정을 설정하면 맞는 Basic과 유효한 CSRF의 관리자 변경은 201이고 저장된다")
        void acceptsMatchingBasicWithCsrfOnWrite() throws Exception {
            mvc.perform(post(ADMIN_CREATE_BRAND).with(csrf()).with(httpBasic(USERNAME, PASSWORD))
                    .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"브랜드\"}"))
                .andExpect(status().isCreated());

            assertThat(brandCount()).isEqualTo(1L);
        }

        @Test
        @DisplayName("비밀번호나 사용자 이름이 틀린 Basic 관리자 요청은 401이고 저장하지 않는다")
        void rejectsWrongBasicWith401() throws Exception {
            mvc.perform(get(ADMIN_LIST).with(httpBasic(USERNAME, "wrong-pass"))).andExpect(status().isUnauthorized());
            mvc.perform(get(ADMIN_LIST).with(httpBasic("someone-else", PASSWORD))).andExpect(status().isUnauthorized());
            mvc.perform(post(ADMIN_CREATE_BRAND).with(csrf()).with(httpBasic(USERNAME, "wrong-pass"))
                    .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"브랜드\"}"))
                .andExpect(status().isUnauthorized());
            assertThat(brandCount()).isZero();
        }

        @Test
        @DisplayName("Basic 인증을 켜도 인증 헤더 없는 관리자 요청은 지금처럼 403이고 CSRF 없는 관리자 변경은 맞는 Basic이어도 403이다")
        void keepsMissingAuthAndCsrfRejection() throws Exception {
            mvc.perform(get(ADMIN_LIST)).andExpect(status().isForbidden());
            mvc.perform(post(ADMIN_CREATE_BRAND).with(httpBasic(USERNAME, PASSWORD))
                    .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"브랜드\"}"))
                .andExpect(status().isForbidden());
            assertThat(brandCount()).isZero();
        }

        @Test
        @DisplayName("Basic 인증을 켜도 고객 API는 X-USER-ID로만 식별하고 CSRF 없이 주문을 만들며 관리자 Basic은 고객 식별을 대신하지 않는다")
        void leavesCustomerApiUnchanged() throws Exception {
            long productId = products.create(brands.create("브랜드").id(), "상품", 1000, 5).id();
            String body = "{\"items\":[{\"productId\":" + productId + ",\"quantity\":1}]}";

            mvc.perform(post("/api/v1/orders").header("X-USER-ID", "1")
                    .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
            mvc.perform(get("/api/v1/orders").header("X-USER-ID", "1")).andExpect(status().isOk());
            mvc.perform(get("/api/v1/orders").with(httpBasic(USERNAME, PASSWORD))).andExpect(status().isUnauthorized());
        }
    }
}
