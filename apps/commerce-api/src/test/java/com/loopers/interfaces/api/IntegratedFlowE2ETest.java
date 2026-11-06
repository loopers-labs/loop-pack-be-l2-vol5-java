package com.loopers.interfaces.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.support.pg.PaymentTestSupport;
import com.loopers.support.pg.TestPgResetExtension;
import com.loopers.support.pg.TestPgServer;
import com.loopers.support.pg.TestPgStatus;
import com.loopers.utils.DatabaseCleanUp;
import com.loopers.utils.RedisCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
@ExtendWith(TestPgResetExtension.class)
class IntegratedFlowE2ETest {

    private final TestPgServer pg = TestPgServer.shared();

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @Autowired
    private RedisCleanUp redisCleanUp;

    // DB를 비우면 목록 revision도 0으로 돌아가므로, 이전 테스트가 같은 revision으로 남긴 목록 캐시도 함께 비운다
    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
        redisCleanUp.truncateAll();
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

        // 7. 고객이 주문을 확정하면 PG 결제가 시작되어 재고가 확보되고 주문은 결제 대기다. 포인트는 쓰지 않는다
        mvc.perform(post("/api/v1/orders/" + orderId + "/confirm")
                .header("X-USER-ID", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(PaymentTestSupport.confirmBodyOf(orderId)))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.data.status").value("PAYMENT_PENDING"))
            .andExpect(jsonPath("$.data.paymentMethod").value("PG"));

        mvc.perform(get("/api/v1/points").header("X-USER-ID", "1"))
            .andExpect(jsonPath("$.data.balance").value(10_000));

        // 7-1. PG가 성공으로 처리한 뒤 고객이 결과를 확인하면, 주문이 확정되고 결제액이 기록된다
        PaymentTestSupport.completeAt(pg, orderId, TestPgStatus.SUCCESS);
        mvc.perform(post("/api/v1/orders/" + orderId + "/payment/reconcile").header("X-USER-ID", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
            .andExpect(jsonPath("$.data.paidAmount").value(10_000));

        mvc.perform(get("/api/v1/points").header("X-USER-ID", "1"))
            .andExpect(jsonPath("$.data.balance").value(10_000));

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

    @DisplayName("브랜드를 삭제하면, 연결 상품이 고객의 목록·상세·새 좋아요·내 좋아요·새 주문에서 빠지고 좋아요 취소만 가능하다.")
    @Test
    void excludesDeletedProductsFromCustomerFlows_afterBrandDeletion() throws Exception {
        // arrange
        Long brandId = createBrand("나이키");
        Long inStockId = createProduct(brandId, "runner", 5);
        Long soldOutId = createProduct(brandId, "slipper", 0);
        Long otherProductId = createProduct(createBrand("아디다스"), "classic", 3);
        like(1L, inStockId);
        like(1L, otherProductId);

        // act
        mvc.perform(delete("/api-admin/v1/brands/" + brandId)
                .with(user("admin").roles("ADMIN"))
                .with(csrf()))
            .andExpect(status().isOk());

        // assert: 목록·상세
        MvcResult list = mvc.perform(get("/api/v1/products")).andExpect(status().isOk()).andReturn();
        assertThat(idsOf(list)).contains(otherProductId).doesNotContain(inStockId, soldOutId);
        mvc.perform(get("/api/v1/products/" + inStockId)).andExpect(status().isNotFound());

        // assert: 새 좋아요는 거절, 내 좋아요 목록에서는 제외, 취소는 허용
        mvc.perform(post("/api/v1/products/" + inStockId + "/likes").header("X-USER-ID", "2"))
            .andExpect(status().isNotFound());
        MvcResult likes = mvc.perform(get("/api/v1/likes").header("X-USER-ID", "1"))
            .andExpect(status().isOk()).andReturn();
        assertThat(idsOf(likes)).containsExactly(otherProductId);
        mvc.perform(delete("/api/v1/products/" + inStockId + "/likes").header("X-USER-ID", "1"))
            .andExpect(status().isOk());

        // assert: 새 주문은 거절
        String orderBody = objectMapper.writeValueAsString(
            Map.of("items", List.of(Map.of("productId", inStockId, "quantity", 1))));
        mvc.perform(post("/api/v1/orders")
                .header("X-USER-ID", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(orderBody))
            .andExpect(status().isNotFound());
    }

    private Long createBrand(String name) throws Exception {
        String response = mvc.perform(post("/api-admin/v1/brands")
                .with(user("admin").roles("ADMIN"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("name", name))))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).path("data").path("id").asLong();
    }

    private Long createProduct(Long brandId, String name, int stock) throws Exception {
        String body = objectMapper.writeValueAsString(
            Map.of("brandId", brandId, "name", name, "price", 10_000, "stock", stock));
        String response = mvc.perform(post("/api-admin/v1/products")
                .with(user("admin").roles("ADMIN"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).path("data").path("id").asLong();
    }

    private void like(Long userId, Long productId) throws Exception {
        mvc.perform(post("/api/v1/products/" + productId + "/likes").header("X-USER-ID", String.valueOf(userId)))
            .andExpect(status().isOk());
    }

    private List<Long> idsOf(MvcResult result) throws Exception {
        List<Long> ids = new ArrayList<>();
        objectMapper.readTree(result.getResponse().getContentAsString()).path("data")
            .forEach(node -> ids.add(node.path("id").asLong()));
        return ids;
    }
}
