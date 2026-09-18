package com.loopers.interfaces.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 브랜드·상품 등록부터 좋아요, 포인트 충전, 주문 생성·확정, 관리자 조회까지
 * 실제 HTTP 계층을 통해 전 도메인이 하나의 흐름으로 이어지는지 검증한다.
 * (다른 E2E 테스트들은 준비 단계에서 리포지토리를 직접 사용하지만, 이 테스트는
 * 관리자의 브랜드·상품 등록부터 전부 실제 API 호출로 이어간다.)
 */
@SpringBootTest
@AutoConfigureMockMvc
class IntegratedFlowE2ETest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("브랜드 등록부터 주문 확정까지, 전체 흐름이 하나로 이어진다.")
    @Test
    void completesFullCommerceFlow() throws Exception {
        // 1. 관리자가 브랜드를 등록한다
        String brandBody = objectMapper.writeValueAsString(Map.of("name", "나이키"));
        String brandResponse = mvc.perform(post("/api-admin/v1/brands")
                .with(user("admin").roles("ADMIN"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(brandBody))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        Long brandId = objectMapper.readTree(brandResponse).path("data").path("id").asLong();

        // 2. 관리자가 그 브랜드로 상품을 등록한다 (재고 5, 가격 10,000)
        String productBody = objectMapper.writeValueAsString(
            Map.of("brandId", brandId, "name", "runner", "price", 10_000, "stock", 5));
        String productResponse = mvc.perform(post("/api-admin/v1/products")
                .with(user("admin").roles("ADMIN"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(productBody))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        Long productId = objectMapper.readTree(productResponse).path("data").path("id").asLong();

        // 3. 고객이 상품 상세에서 브랜드명과 좋아요 수(0)를 확인한다
        mvc.perform(get("/api/v1/products/" + productId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.brandName").value("나이키"))
            .andExpect(jsonPath("$.data.likeCount").value(0));

        // 4. 고객이 좋아요를 누르면, 좋아요 수가 반영된다
        mvc.perform(post("/api/v1/products/" + productId + "/likes").header("X-USER-ID", "1"))
            .andExpect(status().isOk());
        mvc.perform(get("/api/v1/products/" + productId))
            .andExpect(jsonPath("$.data.likeCount").value(1));

        // 5. 고객이 포인트를 충전한다
        String chargeBody = objectMapper.writeValueAsString(Map.of("amount", 10_000));
        mvc.perform(post("/api/v1/points/charge")
                .header("X-USER-ID", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(chargeBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.balance").value(10_000));

        // 6. 고객이 주문을 생성한다 (DRAFT, 아직 재고·잔액 변화 없음)
        String orderBody = objectMapper.writeValueAsString(
            Map.of("items", List.of(Map.of("productId", productId, "quantity", 1))));
        String orderResponse = mvc.perform(post("/api/v1/orders")
                .header("X-USER-ID", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(orderBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("DRAFT"))
            .andExpect(jsonPath("$.data.totalAmount").value(10_000))
            .andReturn().getResponse().getContentAsString();
        Long orderId = objectMapper.readTree(orderResponse).path("data").path("id").asLong();

        // 7. 고객이 주문을 확정하면, 재고가 줄고 포인트가 결제액만큼 차감된다
        mvc.perform(post("/api/v1/orders/" + orderId + "/confirm").header("X-USER-ID", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
            .andExpect(jsonPath("$.data.paidAmount").value(10_000));

        mvc.perform(get("/api/v1/points").header("X-USER-ID", "1"))
            .andExpect(jsonPath("$.data.balance").value(0));

        // 8. 고객이 본인 주문 상세를 다시 조회하면, 확정된 상태 그대로 조회된다
        mvc.perform(get("/api/v1/orders/" + orderId).header("X-USER-ID", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("CONFIRMED"));

        // 9. 관리자는 해당 주문과 줄어든 재고를 조회할 수 있다
        mvc.perform(get("/api-admin/v1/orders/" + orderId).with(user("admin").roles("ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.userId").value(1))
            .andExpect(jsonPath("$.data.status").value("CONFIRMED"));

        mvc.perform(get("/api-admin/v1/products/" + productId).with(user("admin").roles("ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.stock").value(4));
    }
}
