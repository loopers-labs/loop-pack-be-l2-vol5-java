package com.loopers.interfaces.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.user.User;
import com.loopers.infrastructure.user.UserJpaRepository;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 대표 흐름(설계 5.1)을 실제 API 로 이어서 확인한다.
 * 관리자가 브랜드 · 상품 · 재고를 준비하고, 고객이 충전 → 여러 품목 주문 → 확정 → 잔액 · 주문을 조회한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PointOrderFlowE2ETest {

    private static final String USER_ID_HEADER = "X-USER-ID";
    private static final RequestPostProcessor ADMIN = user("admin").roles("ADMIN");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserJpaRepository userJpaRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private ResultActions perform(MockHttpServletRequestBuilder request, String body) throws Exception {
        return mvc.perform(request.contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private long idOf(ResultActions result) throws Exception {
        JsonNode node = objectMapper.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        return node.at("/data/id").asLong();
    }

    private MockHttpServletRequestBuilder asCustomer(MockHttpServletRequestBuilder request, User customer) {
        return request.header(USER_ID_HEADER, String.valueOf(customer.getId()));
    }

    @DisplayName("잔액 0 에서 10,000 원을 충전하고 여러 품목 7,000 원 주문을 확정하면, 잔액 3,000 원 · CONFIRMED 주문 · 줄어든 재고가 조회된다.")
    @Test
    void chargeThenConfirmMultiItemOrder() throws Exception {
        // 관리자: 브랜드 · 상품 등록, 재고 설정
        long brandId = idOf(perform(post("/api-admin/v1/brands").with(ADMIN).with(csrf()), "{\"name\": \"브랜드\"}"));
        long firstId = idOf(perform(post("/api-admin/v1/products").with(ADMIN).with(csrf()),
            "{\"brandId\": " + brandId + ", \"name\": \"첫 번째 상품\", \"price\": 1000}"));
        long secondId = idOf(perform(post("/api-admin/v1/products").with(ADMIN).with(csrf()),
            "{\"brandId\": " + brandId + ", \"name\": \"두 번째 상품\", \"price\": 2500}"));
        perform(put("/api-admin/v1/products/" + firstId + "/stock").with(ADMIN).with(csrf()), "{\"stock\": 10}")
            .andExpect(status().isOk());
        perform(put("/api-admin/v1/products/" + secondId + "/stock").with(ADMIN).with(csrf()), "{\"stock\": 2}")
            .andExpect(status().isOk());

        // 고객: 관리자가 준비한 상품이 고객 조회에 보인다
        User customer = userJpaRepository.save(new User());
        mvc.perform(get("/api/v1/products").param("brandId", String.valueOf(brandId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalCount").value(2))
            .andExpect(jsonPath("$.data.items[0].soldOut").value(false));

        // 고객: 잔액 0 → 10,000 충전
        mvc.perform(asCustomer(get("/api/v1/points"), customer))
            .andExpect(jsonPath("$.data.balance").value(0));
        perform(asCustomer(post("/api/v1/points/charge"), customer), "{\"amount\": 10000}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.balance").value(10_000));

        // 고객: 여러 품목 주문 생성 (1,000 × 2 + 2,500 × 2 = 7,000) — 차감 없음
        long orderId = idOf(perform(asCustomer(post("/api/v1/orders"), customer),
            "{\"items\": [{\"productId\": " + firstId + ", \"quantity\": 2}, {\"productId\": " + secondId + ", \"quantity\": 2}]}"));
        mvc.perform(asCustomer(get("/api/v1/points"), customer))
            .andExpect(jsonPath("$.data.balance").value(10_000));

        // 고객: 확정
        mvc.perform(asCustomer(post("/api/v1/orders/" + orderId + "/confirm"), customer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
            .andExpect(jsonPath("$.data.totalAmount").value(7_000))
            .andExpect(jsonPath("$.data.paymentAmount").value(7_000));

        // 고객: 잔액 3,000
        mvc.perform(asCustomer(get("/api/v1/points"), customer))
            .andExpect(jsonPath("$.data.balance").value(3_000));

        // 고객: 내 주문 목록 · 상세
        mvc.perform(asCustomer(get("/api/v1/orders"), customer))
            .andExpect(jsonPath("$.data.totalCount").value(1))
            .andExpect(jsonPath("$.data.items[0].id").value(orderId))
            .andExpect(jsonPath("$.data.items[0].status").value("CONFIRMED"))
            .andExpect(jsonPath("$.data.items[0].paymentAmount").value(7_000))
            .andExpect(jsonPath("$.data.items[0].itemCount").value(2));
        mvc.perform(asCustomer(get("/api/v1/orders/" + orderId), customer))
            .andExpect(jsonPath("$.data.items[0].productName").value("첫 번째 상품"))
            .andExpect(jsonPath("$.data.items[0].quantity").value(2))
            .andExpect(jsonPath("$.data.items[1].productName").value("두 번째 상품"))
            .andExpect(jsonPath("$.data.paidAt").isNotEmpty());

        // 재고: 관리자는 수량으로, 고객은 품절 여부로 본다
        mvc.perform(get("/api-admin/v1/products/" + firstId).with(ADMIN))
            .andExpect(jsonPath("$.data.stock").value(8));
        mvc.perform(get("/api-admin/v1/products/" + secondId).with(ADMIN))
            .andExpect(jsonPath("$.data.stock").value(0));
        mvc.perform(get("/api/v1/products/" + secondId))
            .andExpect(jsonPath("$.data.soldOut").value(true));

        // 관리자: 구매자별 주문 조회에 결제 결과가 보인다
        mvc.perform(get("/api-admin/v1/orders").param("userId", String.valueOf(customer.getId())).with(ADMIN))
            .andExpect(jsonPath("$.data.items[0].id").value(orderId))
            .andExpect(jsonPath("$.data.items[0].userId").value(customer.getId()))
            .andExpect(jsonPath("$.data.items[0].status").value("CONFIRMED"));
    }
}
