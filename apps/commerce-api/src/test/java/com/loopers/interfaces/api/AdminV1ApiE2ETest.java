package com.loopers.interfaces.api;

import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.order.OrderItemModel;
import com.loopers.domain.order.OrderModel;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.user.UserModel;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.order.OrderJpaRepository;
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

import java.util.List;

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
class AdminV1ApiE2ETest {

    private final MockMvc mvc;
    private final UserJpaRepository userJpaRepository;
    private final BrandJpaRepository brandJpaRepository;
    private final ProductJpaRepository productJpaRepository;
    private final OrderJpaRepository orderJpaRepository;
    private final DatabaseCleanUp databaseCleanUp;

    @Autowired
    public AdminV1ApiE2ETest(
        MockMvc mvc,
        UserJpaRepository userJpaRepository,
        BrandJpaRepository brandJpaRepository,
        ProductJpaRepository productJpaRepository,
        OrderJpaRepository orderJpaRepository,
        DatabaseCleanUp databaseCleanUp
    ) {
        this.mvc = mvc;
        this.userJpaRepository = userJpaRepository;
        this.brandJpaRepository = brandJpaRepository;
        this.productJpaRepository = productJpaRepository;
        this.orderJpaRepository = orderJpaRepository;
        this.databaseCleanUp = databaseCleanUp;
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("관리자 경계 — ADMIN은 허용하고, 일반 사용자와 미식별 요청은 403으로 거절한다.")
    @Test
    void allowsAdmin_andForbidsUserAndAnonymous() throws Exception {
        mvc.perform(get("/api-admin/v1/brands").with(user("admin").roles("ADMIN")))
            .andExpect(status().isOk());
        mvc.perform(get("/api-admin/v1/brands").with(user("customer").roles("USER")))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api-admin/v1/brands"))
            .andExpect(status().isForbidden());
    }

    @DisplayName("POST /api-admin/v1/brands — 브랜드를 생성하고 저장된 값을 조회한다.")
    @Test
    void createsBrand_andReadsIt() throws Exception {
        mvc.perform(post("/api-admin/v1/brands")
                .with(user("admin").roles("ADMIN")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\": \"새 브랜드\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.name").value("새 브랜드"))
            .andExpect(jsonPath("$.data.deleted").value(false));
    }

    @DisplayName("삭제되지 않은 상품이 연결된 브랜드의 삭제는, 400으로 거절한다. 재고 0인 상품도 포함한다.")
    @Test
    void rejectsBrandDeletion_whenActiveProductExists() throws Exception {
        // arrange
        BrandModel brand = brandJpaRepository.save(new BrandModel("브랜드A"));
        productJpaRepository.save(new ProductModel(brand.getId(), "재고 0 상품", 10_000L, 0));

        // act & assert
        mvc.perform(delete("/api-admin/v1/brands/" + brand.getId())
                .with(user("admin").roles("ADMIN")).with(csrf()))
            .andExpect(status().isBadRequest());
        assertThat(brandJpaRepository.findById(brand.getId()).get().getDeletedAt()).isNull();
    }

    @DisplayName("상품이 모두 삭제된 브랜드는 삭제할 수 있고, 이후 고객 조회에서 404다.")
    @Test
    void deletesBrand_whenAllProductsAreDeleted() throws Exception {
        // arrange
        BrandModel brand = brandJpaRepository.save(new BrandModel("브랜드A"));
        ProductModel product = new ProductModel(brand.getId(), "삭제된 상품", 10_000L, 5);
        product.delete();
        productJpaRepository.save(product);

        // act & assert
        mvc.perform(delete("/api-admin/v1/brands/" + brand.getId())
                .with(user("admin").roles("ADMIN")).with(csrf()))
            .andExpect(status().isOk());
        mvc.perform(get("/api/v1/brands/" + brand.getId()))
            .andExpect(status().isNotFound());
    }

    @DisplayName("존재하지 않거나 삭제된 브랜드를 참조하는 상품 생성은, 400으로 거절한다.")
    @Test
    void rejectsProductCreation_whenBrandIsNotActive() throws Exception {
        mvc.perform(post("/api-admin/v1/products")
                .with(user("admin").roles("ADMIN")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"brandId\": 999999, \"name\": \"상품\", \"price\": 10000, \"stock\": 5}"))
            .andExpect(status().isBadRequest());
    }

    @DisplayName("PUT /api-admin/v1/products/{id} — 이름·가격을 수정하고 브랜드는 유지한다.")
    @Test
    void updatesProduct_keepingBrand() throws Exception {
        // arrange
        BrandModel brand = brandJpaRepository.save(new BrandModel("브랜드A"));
        ProductModel product = productJpaRepository.save(new ProductModel(brand.getId(), "이전 이름", 10_000L, 5));

        // act & assert
        mvc.perform(put("/api-admin/v1/products/" + product.getId())
                .with(user("admin").roles("ADMIN")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\": \"새 이름\", \"price\": 12000}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.name").value("새 이름"))
            .andExpect(jsonPath("$.data.price").value(12000))
            .andExpect(jsonPath("$.data.brandId").value(brand.getId().intValue()));
    }

    @DisplayName("PUT /api-admin/v1/products/{id}/stock — 0 이상의 최종 수량으로 설정하고, 음수는 400으로 거절한다.")
    @Test
    void changesStock_toFinalQuantity() throws Exception {
        // arrange
        BrandModel brand = brandJpaRepository.save(new BrandModel("브랜드A"));
        ProductModel product = productJpaRepository.save(new ProductModel(brand.getId(), "상품", 10_000L, 5));

        // act & assert
        mvc.perform(put("/api-admin/v1/products/" + product.getId() + "/stock")
                .with(user("admin").roles("ADMIN")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"quantity\": 0}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.stock").value(0));
        mvc.perform(put("/api-admin/v1/products/" + product.getId() + "/stock")
                .with(user("admin").roles("ADMIN")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"quantity\": -1}"))
            .andExpect(status().isBadRequest());
        assertThat(productJpaRepository.findById(product.getId()).get().getStock()).isEqualTo(0);
    }

    @DisplayName("DELETE /api-admin/v1/products/{id} — 삭제 후 고객 조회는 404, 관리자 조회는 deleted=true다.")
    @Test
    void deletesProduct_softly() throws Exception {
        // arrange
        BrandModel brand = brandJpaRepository.save(new BrandModel("브랜드A"));
        ProductModel product = productJpaRepository.save(new ProductModel(brand.getId(), "상품", 10_000L, 5));

        // act & assert
        mvc.perform(delete("/api-admin/v1/products/" + product.getId())
                .with(user("admin").roles("ADMIN")).with(csrf()))
            .andExpect(status().isOk());
        mvc.perform(get("/api/v1/products/" + product.getId()))
            .andExpect(status().isNotFound());
        mvc.perform(get("/api-admin/v1/products/" + product.getId())
                .with(user("admin").roles("ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.deleted").value(true));
    }

    @DisplayName("GET /api-admin/v1/orders — 구매자별 주문의 품목·상태·금액·결제 결과를 조회한다.")
    @Test
    void returnsOrders_withBuyerInformation() throws Exception {
        // arrange
        UserModel buyer = userJpaRepository.save(new UserModel("구매자"));
        BrandModel brand = brandJpaRepository.save(new BrandModel("브랜드A"));
        ProductModel product = productJpaRepository.save(new ProductModel(brand.getId(), "상품", 3_500L, 5));
        OrderModel order = new OrderModel(buyer.getId(), List.of(
            new OrderItemModel(product.getId(), 2, product.getPrice())
        ));
        order.confirm();
        OrderModel savedOrder = orderJpaRepository.save(order);

        // act & assert
        mvc.perform(get("/api-admin/v1/orders").with(user("admin").roles("ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalCount").value(1))
            .andExpect(jsonPath("$.data.items[0].userId").value(buyer.getId().intValue()))
            .andExpect(jsonPath("$.data.items[0].status").value("CONFIRMED"))
            .andExpect(jsonPath("$.data.items[0].paidAmount").value(7000));
        mvc.perform(get("/api-admin/v1/orders/" + savedOrder.getId()).with(user("admin").roles("ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].quantity").value(2))
            .andExpect(jsonPath("$.data.items[0].unitPrice").value(3500));
        mvc.perform(get("/api-admin/v1/orders/999999").with(user("admin").roles("ADMIN")))
            .andExpect(status().isNotFound());
    }
}
