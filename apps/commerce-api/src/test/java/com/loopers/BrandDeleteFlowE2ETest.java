package com.loopers;

import com.jayway.jsonpath.JsonPath;
import com.loopers.brand.adapter.out.persistence.BrandJpaRepository;
import com.loopers.brand.domain.BrandModel;
import com.loopers.product.adapter.out.persistence.ProductJpaRepository;
import com.loopers.product.domain.ProductModel;
import com.loopers.user.adapter.out.persistence.UserJpaRepository;
import com.loopers.user.domain.UserModel;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
public class BrandDeleteFlowE2ETest {

    private static final String USER_HEADER = "X-USER-ID";
    private static final RequestPostProcessor ADMIN = user("admin").roles("ADMIN");

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


    @DisplayName("브랜드 삭제 → 브랜드·연결 상품(재고 0 포함) 삭제, 다른 브랜드 상품·과거 주문 유지, 삭제 상품의 고객·관리자 사용 제한, 기존 좋아요 취소는 성공, 삭제 전 DRAFT 확정은 409")
    @Test
    void deletesBrandWithItsProducts_andKeepsOthersAndPastOrders() throws Exception {
        // given
        UserModel buyer = userJpaRepository.save(new UserModel("고객"));
        BrandModel nike = brandJpaRepository.save(new BrandModel("나이키", null));
        BrandModel adidas = brandJpaRepository.save(new BrandModel("아디다스", null));
        ProductModel airMax = productJpaRepository.save(new ProductModel(nike.getId(), "에어맥스", 3_000, 5));
        ProductModel soldOut = productJpaRepository.save(new ProductModel(nike.getId(), "품절 상품", 1_000, 0));
        ProductModel superstar = productJpaRepository.save(new ProductModel(adidas.getId(), "슈퍼스타", 2_000, 7));

        chargePoints(buyer, 10_000);

        Long pastOrderId = createOrder(buyer, airMax, 2);
        Long draftOrderId = createOrder(buyer, airMax, 1);

        mockMvc.perform(post("/api/v1/orders/" + pastOrderId + "/confirm").header(USER_HEADER, buyer.getId())).andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/products/" + airMax.getId() + "/likes").header(USER_HEADER, buyer.getId())).andExpect(status().isOk());


        // when
        mockMvc.perform(delete("/api-admin/v1/brands/" + nike.getId()).with(ADMIN).with(csrf())).andExpect(status().isOk());


        // then 1 : 브랜드와 재고 0을 포함한 연결 상품만 삭제
        assertThat(brandJpaRepository.findById(nike.getId()).orElseThrow().getDeletedAt()).isNotNull();
        assertThat(productJpaRepository.findById(airMax.getId()).orElseThrow().getDeletedAt()).isNotNull();
        assertThat(productJpaRepository.findById(soldOut.getId()).orElseThrow().getDeletedAt()).isNotNull();
        assertThat(brandJpaRepository.findById(adidas.getId()).orElseThrow().getDeletedAt()).isNull();
        ProductModel untouched = productJpaRepository.findById(superstar.getId()).orElseThrow();
        assertThat(untouched.getDeletedAt()).isNull();
        assertThat(untouched.getStock()).isEqualTo(7);


        // then 2 : 과거 주문은 주문 당시의 이름·단가·수량·총액·결제액 그대로 조회
        mockMvc.perform(get("/api/v1/orders/" + pastOrderId).header(USER_HEADER, buyer.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.items[0].productName").value("에어맥스"))
                .andExpect(jsonPath("$.data.items[0].unitPrice").value(3_000))
                .andExpect(jsonPath("$.data.items[0].quantity").value(2))
                .andExpect(jsonPath("$.data.totalAmount").value(6_000))
                .andExpect(jsonPath("$.data.paidAmount").value(6_000));


        // then 3 : 고객 목록·상세·새 좋아요·내 좋아요 목록·새 주문서 작성에서 빠짐
        mockMvc.perform(get("/api/v1/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].id").value(superstar.getId()));
        mockMvc.perform(get("/api/v1/products/" + airMax.getId()))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/products/" + soldOut.getId() + "/likes").header(USER_HEADER, buyer.getId()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/users/" + buyer.getId() + "/likes").header(USER_HEADER, buyer.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
        mockMvc.perform(post("/api/v1/orders").header(USER_HEADER, buyer.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\": [{\"productId\": " + airMax.getId() + ", \"quantity\": 1}]}"))
                .andExpect(status().isNotFound());


        // then 4 : 기존 좋아요 취소는 그대로 성공
        mockMvc.perform(delete("/api/v1/products/" + airMax.getId() + "/likes").header(USER_HEADER, buyer.getId())).andExpect(status().isOk());


        // then 5 : 관리자 수정, 재고 변경은 거절
        mockMvc.perform(put("/api-admin/v1/products/" + airMax.getId()).with(ADMIN).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"에어맥스2\", \"price\": 4000}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(put("/api-admin/v1/products/" + airMax.getId() + "/stock").with(ADMIN).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stock\": 100}"))
                .andExpect(status().isNotFound());


        // then 6 : 삭제 전에 만든 주문서 Draft는 확정 불가, 재고/잔액/주문 상태는 바뀌지 않는다.
        mockMvc.perform(post("/api/v1/orders/" + draftOrderId + "/confirm").header(USER_HEADER, buyer.getId()))
                .andExpect(status().isConflict());
        mockMvc.perform(get("/api/v1/orders/" + draftOrderId).header(USER_HEADER, buyer.getId()))
                .andExpect(jsonPath("$.data.status").value("DRAFT"));
        mockMvc.perform(get("/api/v1/points").header(USER_HEADER, buyer.getId()))
                .andExpect(jsonPath("$.data.balance").value(4_000));
        assertThat(productJpaRepository.findById(airMax.getId()).orElseThrow().getStock()).isEqualTo(3);
    }


    private void chargePoints(UserModel user, int amount) throws Exception {
        mockMvc.perform(post("/api/v1/points/charge").header(USER_HEADER, user.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\": " + amount + "}"))
                .andExpect(status().isOk());
    }

    private Long createOrder(UserModel user, ProductModel product, int quantity) throws Exception {
        String body = mockMvc.perform(post("/api/v1/orders").header(USER_HEADER, user.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\": [{\"productId\": " + product.getId() + ", \"quantity\": " + quantity + "}]}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.parse(body).read("$.data.id", Long.class);
    }
}
