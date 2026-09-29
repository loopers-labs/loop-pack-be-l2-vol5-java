package com.loopers.interfaces.api.product;

import com.loopers.application.product.ProductFacade;
import com.loopers.application.brand.BrandFacade;
import com.loopers.domain.brand.BrandService;
import com.loopers.domain.common.Quantity;
import com.loopers.domain.product.Price;
import com.loopers.domain.product.ProductService;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ProductAdminV1ApiTest {

    private static final String ADMIN = "/api-admin/v1/products";

    private final MockMvc mvc;
    private final DatabaseCleanUp databaseCleanUp;
    private final BrandFacade brandFacade;
    private final ProductFacade productFacade;

    private Long brandId;

    @Autowired
    ProductAdminV1ApiTest(
        MockMvc mvc, BrandService brandService, ProductService productService, DatabaseCleanUp databaseCleanUp,
        BrandFacade brandFacade,
        ProductFacade productFacade
    ) {
        this.mvc = mvc;
        this.databaseCleanUp = databaseCleanUp;
        this.brandFacade = brandFacade;
        this.productFacade = productFacade;
    }

    @BeforeEach
    void setUp() {
        brandId = brandFacade.register("무신사", "패션 플랫폼").getId();
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private static MockHttpServletRequestBuilder asAdmin(MockHttpServletRequestBuilder builder) {
        return builder.with(user("admin").roles("ADMIN")).with(csrf());
    }

    @Nested
    @DisplayName("등록 — PRODUCT-003 은 Facade 가 조립한다")
    class Register {
        @DisplayName("살아 있는 브랜드로 등록하면 201 이고 재고가 0으로 함께 생긴다")
        @Test
        void creates() throws Exception {
            mvc.perform(asAdmin(post(ADMIN))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"brandId\":" + brandId + ",\"name\":\"코트\",\"price\":129000}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.brandId").value(brandId))
                .andExpect(jsonPath("$.data.quantity").value(0));
        }

        @DisplayName("Q-3 · 없는 브랜드는 400 · BRAND_NOT_AVAILABLE 이다. 404 가 아니다")
        @Test
        void rejectsUnknownBrand() throws Exception {
            mvc.perform(asAdmin(post(ADMIN))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"brandId\":9999,\"name\":\"코트\",\"price\":129000}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.meta.errorCode").value("BRAND_NOT_AVAILABLE"));
        }

        @DisplayName("BRAND-006 · 삭제된 브랜드도 없는 브랜드와 같게 답한다")
        @Test
        void rejectsDeletedBrandTheSameWay() throws Exception {
            brandFacade.delete(brandId);

            mvc.perform(asAdmin(post(ADMIN))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"brandId\":" + brandId + ",\"name\":\"코트\",\"price\":129000}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.meta.errorCode").value("BRAND_NOT_AVAILABLE"));
        }

        @DisplayName("PRODUCT-002 · 가격이 0이면 400 이다 — VO 가 역직렬화 단계에서 거절한다")
        @Test
        void rejectsZeroPrice() throws Exception {
            mvc.perform(asAdmin(post(ADMIN))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"brandId\":" + brandId + ",\"name\":\"코트\",\"price\":0}"))
                .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("수정 · 재고")
    class Modify {
        @DisplayName("PRODUCT-004 · 수정은 이름과 가격만 받는다. 브랜드는 그대로다")
        @Test
        void updatesKeepingBrand() throws Exception {
            Long productId = productFacade.register(brandId, "코트", Price.of(129_000)).getId();

            mvc.perform(asAdmin(put(ADMIN + "/" + productId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"트렌치코트\",\"price\":150000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("트렌치코트"))
                .andExpect(jsonPath("$.data.brandId").value(brandId));
        }

        @DisplayName("PRODUCT-021 · 재고는 최종 수량으로 설정된다")
        @Test
        void adjustsStock() throws Exception {
            Long productId = productFacade.register(brandId, "코트", Price.of(129_000)).getId();
            productFacade.adjustStock(productId, Quantity.of(30));

            mvc.perform(asAdmin(put(ADMIN + "/" + productId + "/stock"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"quantity\":12}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.quantity").value(12));
        }

        @DisplayName("PRODUCT-020 · 음수 재고는 400 이다")
        @Test
        void rejectsNegativeStock() throws Exception {
            Long productId = productFacade.register(brandId, "코트", Price.of(129_000)).getId();

            mvc.perform(asAdmin(put(ADMIN + "/" + productId + "/stock"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"quantity\":-1}"))
                .andExpect(status().isBadRequest());
        }

        @DisplayName("PRODUCT-006 · 삭제된 상품의 재고 변경은 404 다")
        @Test
        void rejectsStockChangeOfDeleted() throws Exception {
            Long productId = productFacade.register(brandId, "코트", Price.of(129_000)).getId();
            productFacade.delete(productId);

            mvc.perform(asAdmin(put(ADMIN + "/" + productId + "/stock"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"quantity\":5}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.meta.errorCode").value("PRODUCT_NOT_FOUND"));
        }

        @DisplayName("관리자 상세에는 재고 수량이 있다 — 고객 응답과 타입이 다르다")
        @Test
        void adminDetailHasQuantity() throws Exception {
            Long productId = productFacade.register(brandId, "코트", Price.of(129_000)).getId();
            productFacade.adjustStock(productId, Quantity.of(7));

            mvc.perform(asAdmin(get(ADMIN + "/" + productId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.quantity").value(7));
        }
    }

    @Nested
    @DisplayName("거절되면 아무것도 바뀌지 않는다")
    class RejectionKeepsState {
        @DisplayName("PRODUCT-001 · 이름이 100자를 넘어 거절되면 기존 이름·가격이 그대로다")
        @Test
        void rejectedUpdateKeepsValues() throws Exception {
            Long productId = productFacade.register(brandId, "코트", Price.of(129_000)).getId();

            mvc.perform(asAdmin(put(ADMIN + "/" + productId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"" + "가".repeat(101) + "\",\"price\":150000}"))
                .andExpect(status().isBadRequest());

            mvc.perform(asAdmin(get(ADMIN + "/" + productId)))
                .andExpect(jsonPath("$.data.name").value("코트"))
                .andExpect(jsonPath("$.data.price").value(129000));
        }

        @DisplayName("접근이 거절되면 상품이 만들어지지 않는다 — 403 과 함께 저장도 없다")
        @Test
        void rejectedAccessCreatesNothing() throws Exception {
            mvc.perform(post(ADMIN).with(user("customer").roles("USER")).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"brandId\":" + brandId + ",\"name\":\"코트\",\"price\":129000}"))
                .andExpect(status().isForbidden());

            mvc.perform(asAdmin(get("/api-admin/v1/products/1")))
                .andExpect(status().isNotFound());
        }
    }
}
