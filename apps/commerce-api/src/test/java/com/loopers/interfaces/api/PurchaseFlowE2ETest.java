package com.loopers.interfaces.api;

import com.jayway.jsonpath.JsonPath;
import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.user.UserModel;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 연결 흐름 (plan.md 12장): 충전 → 여러 품목 주문 생성 → 확정 → 내 주문·잔액 조회를 HTTP만으로 이어서 확인한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PurchaseFlowE2ETest {

    private static final String USER_HEADER = "X-USER-ID";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserJpaRepository userJpaRepository;

    @Autowired
    private BrandJpaRepository brandJpaRepository;

    @Autowired
    private ProductJpaRepository productJpaRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("PNT-02·ORD-01·ORD-05 잔액 0원 → 10,000원 충전 → 7,000원 주문 생성(DRAFT) → 확정 → 잔액 3,000원, 주문 CONFIRMED·결제액 7,000원, 재고 감소")
    @Test
    void chargeCreateConfirmAndCheckBalance() throws Exception {
        // arrange
        UserModel user = userJpaRepository.save(new UserModel("고객"));
        BrandModel nike = brandJpaRepository.save(new BrandModel("나이키", null));
        ProductModel airMax = productJpaRepository.save(new ProductModel(nike.getId(), "에어맥스", 1_000, 10));
        ProductModel airForce = productJpaRepository.save(new ProductModel(nike.getId(), "에어포스", 2_000, 10));

        // act & assert: 충전 전 잔액 0원
        mockMvc.perform(get("/api/v1/points").header(USER_HEADER, user.getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.balance").value(0));

        // act & assert: 10,000원 충전
        mockMvc.perform(post("/api/v1/points/charge").header(USER_HEADER, user.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\": 10000}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.balance").value(10_000));

        // act & assert: 에어맥스 3개 + 에어포스 2개 = 7,000원 주문은 DRAFT이고 아직 아무것도 차감하지 않는다
        String created = mockMvc.perform(post("/api/v1/orders").header(USER_HEADER, user.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"items\": ["
                    + "{\"productId\": " + airMax.getId() + ", \"quantity\": 3},"
                    + "{\"productId\": " + airForce.getId() + ", \"quantity\": 2}]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("DRAFT"))
            .andExpect(jsonPath("$.data.totalAmount").value(7_000))
            .andReturn().getResponse().getContentAsString();
        Long orderId = JsonPath.parse(created).read("$.data.id", Long.class);
        mockMvc.perform(get("/api/v1/points").header(USER_HEADER, user.getId()))
            .andExpect(jsonPath("$.data.balance").value(10_000));

        // act & assert: 확정
        mockMvc.perform(post("/api/v1/orders/" + orderId + "/confirm").header(USER_HEADER, user.getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
            .andExpect(jsonPath("$.data.paidAmount").value(7_000));

        // assert: 잔액·내 주문·재고
        mockMvc.perform(get("/api/v1/points").header(USER_HEADER, user.getId()))
            .andExpect(jsonPath("$.data.balance").value(3_000));
        mockMvc.perform(get("/api/v1/orders").header(USER_HEADER, user.getId()))
            .andExpect(jsonPath("$.data.content[0].id").value(orderId))
            .andExpect(jsonPath("$.data.content[0].status").value("CONFIRMED"))
            .andExpect(jsonPath("$.data.content[0].paidAmount").value(7_000));
        assertThat(productJpaRepository.findById(airMax.getId()).orElseThrow().getStock()).isEqualTo(7);
        assertThat(productJpaRepository.findById(airForce.getId()).orElseThrow().getStock()).isEqualTo(8);
    }
}
