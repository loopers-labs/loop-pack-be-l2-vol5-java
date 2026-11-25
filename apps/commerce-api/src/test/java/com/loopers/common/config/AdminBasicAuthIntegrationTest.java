package com.loopers.common.config;

import com.loopers.domains.brand.app.BrandApplicationService;
import com.loopers.domains.product.app.ProductApplicationService;
import com.loopers.domains.user.out.UserJpaEntity;
import com.loopers.domains.user.out.UserJpaRepository;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.servlet.http.Cookie;
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
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 관리자 HTTP Basic은 local·dev 프로필 설정이 켜고 계정은 환경 변수로만 받는다. test 프로필에서는 같은 프로퍼티를 직접 넣어 증명한다.
// 비밀번호 값은 이 테스트 전용이며 운영 계정과 무관하다.
// 바깥 클래스에 Spring을 두면 바깥 인스턴스의 주입 필드가 프로퍼티 없는 바깥 컨텍스트에서 온다. 그래서 장면마다 자기 컨텍스트로 주입받는다.
class AdminBasicAuthIntegrationTest {
    private static final String ADMIN_LIST = "/api-admin/v1/orders";
    private static final String ADMIN_CREATE_BRAND = "/api-admin/v1/brands";
    private static final String USERNAME = "ops-admin";
    private static final String PASSWORD = "test-only-admin-pass";
    private static final String CSRF_COOKIE = "XSRF-TOKEN";
    private static final String CSRF_HEADER = "X-XSRF-TOKEN";
    private static final String BRAND_BODY = "{\"name\":\"브랜드\"}";

    @SpringBootTest
    @AutoConfigureMockMvc
    abstract static class Scene {
        @Autowired protected MockMvc mvc;
        @Autowired protected BrandApplicationService brands;
        @Autowired protected ProductApplicationService products;
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

        protected long brandCount() {
            return jdbc.queryForObject("select count(*) from brands", Long.class);
        }
    }

    @Nested
    @TestPropertySource(properties = {
        "commerce.admin.basic-auth.username=" + USERNAME,
        "commerce.admin.basic-auth.password=" + PASSWORD})
    class BasicAuthNotEnabled extends Scene {
        @Test
        @DisplayName("Basic 인증을 켜지 않은 프로필은 계정 값이 있어도 맞는 Basic 요청을 지금처럼 403으로 거절한다")
        void rejectsEvenMatchingBasic() throws Exception {
            mvc.perform(get(ADMIN_LIST).with(httpBasic(USERNAME, PASSWORD))).andExpect(status().isForbidden());
            mvc.perform(get(ADMIN_LIST)).andExpect(status().isForbidden());
        }
    }

    @Nested
    @TestPropertySource(properties = "commerce.admin.basic-auth.enabled=true")
    class BasicAuthEnabledWithoutAccount extends Scene {
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
    class BasicAuthEnabledWithAccount extends Scene {
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

    // 관리자 변경은 GET으로 받은 XSRF-TOKEN 쿠키 값을 X-XSRF-TOKEN 헤더로 되돌려 보내야 통과한다. 테스트 지원 csrf()를 쓰지 않고 실제 쿠키·헤더로 확인한다.
    @Nested
    @TestPropertySource(properties = {
        "commerce.admin.basic-auth.enabled=true",
        "commerce.admin.basic-auth.username=" + USERNAME,
        "commerce.admin.basic-auth.password=" + PASSWORD})
    class CookieCsrfTokenFlow extends Scene {
        @Test
        @DisplayName("맞는 Basic 관리자 조회 응답은 스크립트가 읽을 수 있는(HttpOnly 아님) XSRF-TOKEN 쿠키를 준다")
        void issuesReadableCsrfCookieOnAdminRead() throws Exception {
            MvcResult result = mvc.perform(get(ADMIN_LIST).with(httpBasic(USERNAME, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();

            Cookie cookie = result.getResponse().getCookie(CSRF_COOKIE);
            assertThat(cookie).as("관리자 조회 응답의 XSRF-TOKEN 쿠키").isNotNull();
            assertThat(cookie.getValue()).isNotBlank();
            assertThat(cookie.isHttpOnly()).isFalse();
        }

        @Test
        @DisplayName("조회로 받은 XSRF-TOKEN 쿠키 값을 쿠키와 X-XSRF-TOKEN 헤더로 붙인 Basic 관리자 변경은 201이고 저장된다")
        void acceptsWriteWithTokenFromReadCookie() throws Exception {
            Cookie cookie = mvc.perform(get(ADMIN_LIST).with(httpBasic(USERNAME, PASSWORD)))
                .andReturn().getResponse().getCookie(CSRF_COOKIE);
            assertThat(cookie).as("관리자 조회 응답의 XSRF-TOKEN 쿠키").isNotNull();

            mvc.perform(post(ADMIN_CREATE_BRAND).with(httpBasic(USERNAME, PASSWORD))
                    .cookie(cookie).header(CSRF_HEADER, cookie.getValue())
                    .contentType(MediaType.APPLICATION_JSON).content(BRAND_BODY))
                .andExpect(status().isCreated());

            assertThat(brandCount()).isEqualTo(1L);
        }

        @Test
        @DisplayName("X-XSRF-TOKEN 헤더 값이 XSRF-TOKEN 쿠키 값과 다르면 맞는 Basic이어도 관리자 변경은 403이고 저장하지 않는다")
        void rejectsWriteWithMismatchedHeader() throws Exception {
            mvc.perform(post(ADMIN_CREATE_BRAND).with(httpBasic(USERNAME, PASSWORD))
                    .cookie(new Cookie(CSRF_COOKIE, "cookie-token-value")).header(CSRF_HEADER, "other-token-value")
                    .contentType(MediaType.APPLICATION_JSON).content(BRAND_BODY))
                .andExpect(status().isForbidden());

            assertThat(brandCount()).isZero();
        }

        @Test
        @DisplayName("토큰이 전혀 없거나 쿠키만 있고 X-XSRF-TOKEN 헤더가 없는 관리자 변경은 맞는 Basic이어도 403이고 저장하지 않는다")
        void rejectsWriteWithoutHeaderToken() throws Exception {
            mvc.perform(post(ADMIN_CREATE_BRAND).with(httpBasic(USERNAME, PASSWORD))
                    .contentType(MediaType.APPLICATION_JSON).content(BRAND_BODY))
                .andExpect(status().isForbidden());
            mvc.perform(post(ADMIN_CREATE_BRAND).with(httpBasic(USERNAME, PASSWORD))
                    .cookie(new Cookie(CSRF_COOKIE, "cookie-token-value"))
                    .contentType(MediaType.APPLICATION_JSON).content(BRAND_BODY))
                .andExpect(status().isForbidden());

            assertThat(brandCount()).isZero();
        }

        @Test
        @DisplayName("고객 API 응답에는 XSRF-TOKEN 쿠키가 생기지 않고 고객 주문은 CSRF 토큰 없이 지금처럼 201이다")
        void leavesCustomerApiWithoutCsrfCookie() throws Exception {
            long productId = products.create(brands.create("브랜드").id(), "상품", 1000, 5).id();
            String body = "{\"items\":[{\"productId\":" + productId + ",\"quantity\":1}]}";

            MvcResult created = mvc.perform(post("/api/v1/orders").header("X-USER-ID", "1")
                    .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn();
            MvcResult listed = mvc.perform(get("/api/v1/orders").header("X-USER-ID", "1"))
                .andExpect(status().isOk())
                .andReturn();

            assertThat(created.getResponse().getCookie(CSRF_COOKIE)).isNull();
            assertThat(listed.getResponse().getCookie(CSRF_COOKIE)).isNull();
        }
    }
}
