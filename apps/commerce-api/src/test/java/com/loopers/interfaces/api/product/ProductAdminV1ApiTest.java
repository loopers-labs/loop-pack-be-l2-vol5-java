package com.loopers.interfaces.api.product;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.product.StockChangeCause;
import com.loopers.fixture.BrandFixture;
import com.loopers.fixture.ProductFixture;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.infrastructure.product.StockHistoryJpaRepository;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("관리자 상품 API 는 상품을 등록·조회·수정·삭제하고 재고를 변경한다.")
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(roles = "ADMIN")
class ProductAdminV1ApiTest {

    private static final String ENDPOINT = "/api-admin/v1/products";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private BrandFixture brandFixture;
    @Autowired
    private ProductFixture productFixture;
    @Autowired
    private ProductJpaRepository productJpaRepository;
    @Autowired
    private StockHistoryJpaRepository stockHistoryJpaRepository;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private String json(Map<String, Object> body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    private static Map<String, Object> body(Object... keyValues) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    @DisplayName("GET /api-admin/v1/products")
    @Nested
    class GetProducts {
        @DisplayName("[잠정] 삭제된 상품을 제외하고 최신 등록순 페이지로 반환한다.")
        @Test
        void returnsActiveProductPage() throws Exception {
            productFixture.createProduct("첫째", 1_000L, 1L);
            productFixture.createProduct("둘째", 2_000L, 2L);
            productFixture.createDeletedProduct("삭제됨", 3_000L, 3L);

            mockMvc.perform(get(ENDPOINT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.items[0].name").value("둘째"))
                .andExpect(jsonPath("$.data.items[1].name").value("첫째"));
        }

        @DisplayName("브랜드명과 현재 재고 수량을 포함하고 좋아요 수는 포함하지 않는다.")
        @Test
        void includesBrandNameAndStockOnly() throws Exception {
            BrandModel nike = brandFixture.createBrand("나이키");
            productFixture.createProduct(nike.getId(), "운동화", 89_000L, 7L);

            mockMvc.perform(get(ENDPOINT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].brandId").value(nike.getId()))
                .andExpect(jsonPath("$.data.items[0].brandName").value("나이키"))
                .andExpect(jsonPath("$.data.items[0].price").value(89_000))
                .andExpect(jsonPath("$.data.items[0].stockQuantity").value(7))
                .andExpect(jsonPath("$.data.items[0].likeCount").doesNotExist());
        }

        @DisplayName("지원하지 않는 sort 는 400 INVALID_SORT 로 거절한다.")
        @Test
        void rejectsUnsupportedSort() throws Exception {
            mockMvc.perform(get(ENDPOINT).param("sort", "price_asc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.meta.errorCode").value("INVALID_SORT"));
        }
    }

    @DisplayName("POST /api-admin/v1/products")
    @Nested
    class CreateProduct {
        @DisplayName("재고 0 인 상품을 저장하고 201 로 반환한다.")
        @Test
        void createsProduct() throws Exception {
            BrandModel nike = brandFixture.createBrand("나이키");

            mockMvc.perform(post(ENDPOINT).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content(json(body("brandId", nike.getId(), "name", "운동화", "price", 89_000L))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.brandId").value(nike.getId()))
                .andExpect(jsonPath("$.data.brandName").value("나이키"))
                .andExpect(jsonPath("$.data.name").value("운동화"))
                .andExpect(jsonPath("$.data.price").value(89_000))
                .andExpect(jsonPath("$.data.stockQuantity").value(0));

            assertThat(productJpaRepository.findAll()).hasSize(1);
        }

        @DisplayName("존재하지 않는 브랜드는 404 BRAND_NOT_FOUND 로 거절하고 저장하지 않는다.")
        @Test
        void rejectsUnknownBrand() throws Exception {
            mockMvc.perform(post(ENDPOINT).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content(json(body("brandId", 999999L, "name", "운동화", "price", 10_000L))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.meta.errorCode").value("BRAND_NOT_FOUND"));

            assertThat(productJpaRepository.findAll()).isEmpty();
        }

        @DisplayName("[잠정] 허용 범위를 넘는 가격은 400 INVALID_PRODUCT_PRICE 로 거절한다.")
        @Test
        void rejectsPriceOutOfRange() throws Exception {
            BrandModel nike = brandFixture.createBrand("나이키");

            mockMvc.perform(post(ENDPOINT).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content(json(body("brandId", nike.getId(), "name", "운동화", "price", 100_000_001L))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.meta.errorCode").value("INVALID_PRODUCT_PRICE"));

            assertThat(productJpaRepository.findAll()).isEmpty();
        }

        @DisplayName("name 이 없으면 400 INVALID_PRODUCT_NAME 으로 거절한다.")
        @Test
        void rejectsMissingName() throws Exception {
            BrandModel nike = brandFixture.createBrand("나이키");

            mockMvc.perform(post(ENDPOINT).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content(json(body("brandId", nike.getId(), "price", 10_000L))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.meta.errorCode").value("INVALID_PRODUCT_NAME"));
        }

        @DisplayName("price 가 없으면 400 INVALID_PRODUCT_PRICE 로 거절한다.")
        @Test
        void rejectsMissingPrice() throws Exception {
            BrandModel nike = brandFixture.createBrand("나이키");

            mockMvc.perform(post(ENDPOINT).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content(json(body("brandId", nike.getId(), "name", "운동화"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.meta.errorCode").value("INVALID_PRODUCT_PRICE"));
        }

        @DisplayName("brandId 가 없으면 400 INVALID_REQUEST 로 거절한다.")
        @Test
        void rejectsMissingBrandId() throws Exception {
            mockMvc.perform(post(ENDPOINT).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content(json(body("name", "운동화", "price", 10_000L))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.meta.errorCode").value("INVALID_REQUEST"));
        }
    }

    @DisplayName("GET /api-admin/v1/products/{productId}")
    @Nested
    class GetProduct {
        @DisplayName("활성 상품 상세를 반환한다.")
        @Test
        void returnsProduct() throws Exception {
            ProductModel shoes = productFixture.createProduct("운동화", 89_000L, 7L);

            mockMvc.perform(get(ENDPOINT + "/" + shoes.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(shoes.getId()))
                .andExpect(jsonPath("$.data.stockQuantity").value(7));
        }

        @DisplayName("[잠정] 삭제된 상품은 404 PRODUCT_NOT_FOUND 로 응답한다.")
        @Test
        void rejectsDeletedProduct() throws Exception {
            ProductModel deleted = productFixture.createDeletedProduct("단종 운동화", 10_000L, 3L);

            mockMvc.perform(get(ENDPOINT + "/" + deleted.getId()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.meta.errorCode").value("PRODUCT_NOT_FOUND"));
        }
    }

    @DisplayName("PUT /api-admin/v1/products/{productId}")
    @Nested
    class UpdateProduct {
        @DisplayName("이름과 가격을 수정하고 브랜드 관계와 재고를 유지한다.")
        @Test
        void updatesProduct() throws Exception {
            BrandModel nike = brandFixture.createBrand("나이키");
            ProductModel shoes = productFixture.createProduct(nike.getId(), "운동화", 89_000L, 7L);

            mockMvc.perform(put(ENDPOINT + "/" + shoes.getId()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content(json(body("name", "러닝화", "price", 99_000L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("러닝화"))
                .andExpect(jsonPath("$.data.price").value(99_000))
                .andExpect(jsonPath("$.data.brandId").value(nike.getId()))
                .andExpect(jsonPath("$.data.stockQuantity").value(7));
        }

        @DisplayName("잘못된 이름은 400 INVALID_PRODUCT_NAME 으로 거절하고 기존 값을 유지한다.")
        @Test
        void rejectsInvalidName() throws Exception {
            ProductModel shoes = productFixture.createProduct("운동화", 89_000L, 7L);

            mockMvc.perform(put(ENDPOINT + "/" + shoes.getId()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content(json(body("name", " ", "price", 99_000L))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.meta.errorCode").value("INVALID_PRODUCT_NAME"));

            ProductModel saved = productJpaRepository.findById(shoes.getId()).orElseThrow();
            assertAll(
                () -> assertThat(saved.getName()).isEqualTo("운동화"),
                () -> assertThat(saved.getPrice().toWon()).isEqualTo(89_000L)
            );
        }

        @DisplayName("삭제된 상품은 404 PRODUCT_NOT_FOUND 로 거절한다.")
        @Test
        void rejectsDeletedProduct() throws Exception {
            ProductModel deleted = productFixture.createDeletedProduct("단종 운동화", 10_000L, 3L);

            mockMvc.perform(put(ENDPOINT + "/" + deleted.getId()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content(json(body("name", "새이름", "price", 20_000L))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.meta.errorCode").value("PRODUCT_NOT_FOUND"));
        }

        @DisplayName("없는 상품에 price 가 빠진 요청은 값 판단보다 먼저 404 PRODUCT_NOT_FOUND 로 거절한다.")
        @Test
        void rejectsUnknownProductBeforeValidatingPrice() throws Exception {
            ProductModel deleted = productFixture.createDeletedProduct("단종 운동화", 10_000L, 3L);

            mockMvc.perform(put(ENDPOINT + "/" + deleted.getId()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content(json(body("name", "새이름"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.meta.errorCode").value("PRODUCT_NOT_FOUND"));
        }
    }

    @DisplayName("DELETE /api-admin/v1/products/{productId}")
    @Nested
    class DeleteProduct {
        @DisplayName("삭제 시각을 기록하고 200 과 빈 데이터로 응답한다.")
        @Test
        void deletesProduct() throws Exception {
            ProductModel shoes = productFixture.createProduct("운동화", 10_000L, 3L);

            mockMvc.perform(delete(ENDPOINT + "/" + shoes.getId()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").doesNotExist());

            assertThat(productJpaRepository.findById(shoes.getId()).orElseThrow().getDeletedAt()).isNotNull();
        }

        @DisplayName("이미 삭제된 상품은 404 PRODUCT_NOT_FOUND 로 거절한다.")
        @Test
        void rejectsDeletedProduct() throws Exception {
            ProductModel deleted = productFixture.createDeletedProduct("단종 운동화", 10_000L, 3L);

            mockMvc.perform(delete(ENDPOINT + "/" + deleted.getId()).with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.meta.errorCode").value("PRODUCT_NOT_FOUND"));
        }
    }

    @DisplayName("PUT /api-admin/v1/products/{productId}/stock")
    @Nested
    class ChangeStock {
        @DisplayName("최종 수량으로 재고를 변경하고 변경 전후 값을 가진 이력을 남긴다.")
        @Test
        void changesStock() throws Exception {
            ProductModel shoes = productFixture.createProduct("운동화", 10_000L, 5L);

            mockMvc.perform(put(ENDPOINT + "/" + shoes.getId() + "/stock").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content(json(body("quantity", 2L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.productId").value(shoes.getId()))
                .andExpect(jsonPath("$.data.quantity").value(2));

            assertAll(
                () -> assertThat(productJpaRepository.findById(shoes.getId()).orElseThrow().getStockQuantity())
                    .isEqualTo(2L),
                () -> assertThat(stockHistoryJpaRepository.findAll()).hasSize(1),
                () -> assertThat(stockHistoryJpaRepository.findAll().get(0).getBeforeQuantity()).isEqualTo(5L),
                () -> assertThat(stockHistoryJpaRepository.findAll().get(0).getAfterQuantity()).isEqualTo(2L),
                () -> assertThat(stockHistoryJpaRepository.findAll().get(0).getCause())
                    .isEqualTo(StockChangeCause.ADMIN_CHANGE)
            );
        }

        @DisplayName("[잠정] 음수 수량은 400 INVALID_STOCK_QUANTITY 로 거절하고 재고와 이력을 유지한다.")
        @Test
        void rejectsNegativeQuantity() throws Exception {
            ProductModel shoes = productFixture.createProduct("운동화", 10_000L, 5L);

            mockMvc.perform(put(ENDPOINT + "/" + shoes.getId() + "/stock").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content(json(body("quantity", -1L))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.meta.errorCode").value("INVALID_STOCK_QUANTITY"));

            assertAll(
                () -> assertThat(productJpaRepository.findById(shoes.getId()).orElseThrow().getStockQuantity())
                    .isEqualTo(5L),
                () -> assertThat(stockHistoryJpaRepository.findAll()).isEmpty()
            );
        }

        @DisplayName("quantity 가 없으면 400 INVALID_STOCK_QUANTITY 로 거절한다.")
        @Test
        void rejectsMissingQuantity() throws Exception {
            ProductModel shoes = productFixture.createProduct("운동화", 10_000L, 5L);

            mockMvc.perform(put(ENDPOINT + "/" + shoes.getId() + "/stock").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.meta.errorCode").value("INVALID_STOCK_QUANTITY"));
        }

        @DisplayName("삭제된 상품의 재고는 404 PRODUCT_NOT_FOUND 로 거절한다.")
        @Test
        void rejectsDeletedProduct() throws Exception {
            ProductModel deleted = productFixture.createDeletedProduct("단종 운동화", 10_000L, 3L);

            mockMvc.perform(put(ENDPOINT + "/" + deleted.getId() + "/stock").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content(json(body("quantity", 1L))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.meta.errorCode").value("PRODUCT_NOT_FOUND"));
        }

        @DisplayName("없는 상품에 quantity 가 빠진 요청은 값 판단보다 먼저 404 PRODUCT_NOT_FOUND 로 거절한다.")
        @Test
        void rejectsUnknownProductBeforeValidatingQuantity() throws Exception {
            ProductModel deleted = productFixture.createDeletedProduct("단종 운동화", 10_000L, 3L);

            mockMvc.perform(put(ENDPOINT + "/" + deleted.getId() + "/stock").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.meta.errorCode").value("PRODUCT_NOT_FOUND"));
        }
    }
}
