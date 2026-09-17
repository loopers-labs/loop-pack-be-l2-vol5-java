package com.loopers.interfaces.api.admin.product;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.product.ProductModel;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.HashMap;
import java.util.Map;

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
class ProductAdminV1ApiE2ETest {

    private static final String ENDPOINT = "/api-admin/v1/products";
    private static final RequestPostProcessor ADMIN = user("admin").roles("ADMIN");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

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

    private String json(Object body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    private String jsonField(MvcResult result, String field) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path(field).asText();
    }

    private BrandModel brand() {
        return brandJpaRepository.save(new BrandModel("나이키", null));
    }

    private ProductModel product(BrandModel brand, int stock) {
        return productJpaRepository.save(new ProductModel(brand.getId(), "에어맥스", 100_000, stock));
    }

    private Map<String, Object> createBody(Long brandId, Object price, Object stock) {
        Map<String, Object> body = new HashMap<>();
        body.put("brandId", brandId);
        body.put("name", "에어맥스");
        body.put("price", price);
        body.put("stock", stock);
        return body;
    }

    @DisplayName("POST /api-admin/v1/products")
    @Nested
    class Create {

        @DisplayName("PRD-01·PRD-02 살아 있는 브랜드와 유효한 값이면 저장하고 관리자 응답(재고·시각 포함)을 돌려준다.")
        @Test
        void createsProduct() throws Exception {
            // arrange
            BrandModel brand = brand();

            // act
            mockMvc.perform(post(ENDPOINT).with(ADMIN).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json(createBody(brand.getId(), 100_000, 5))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.brandId").value(brand.getId()))
                .andExpect(jsonPath("$.data.price").value(100_000))
                .andExpect(jsonPath("$.data.stock").value(5))
                .andExpect(jsonPath("$.data.createdAt").exists());

            // assert
            assertThat(productJpaRepository.findAll()).extracting(ProductModel::getName).containsExactly("에어맥스");
        }

        @DisplayName("PRD-02 삭제된 브랜드를 참조하면 404이고, 저장되지 않는다.")
        @Test
        void rejectsDeletedBrand() throws Exception {
            // arrange
            BrandModel brand = new BrandModel("아디다스", null);
            brand.delete();
            BrandModel deletedBrand = brandJpaRepository.save(brand);

            // act
            mockMvc.perform(post(ENDPOINT).with(ADMIN).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json(createBody(deletedBrand.getId(), 100_000, 5))))
                .andExpect(status().isNotFound());

            // assert
            assertThat(productJpaRepository.count()).isZero();
        }

        @DisplayName("PRD-01 가격 0원이거나 재고가 빠지면 400이고, 저장되지 않는다.")
        @Test
        void rejectsInvalidInput() throws Exception {
            // arrange
            BrandModel brand = brand();

            // act
            mockMvc.perform(post(ENDPOINT).with(ADMIN).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json(createBody(brand.getId(), 0, 5))))
                .andExpect(status().isBadRequest());
            mockMvc.perform(post(ENDPOINT).with(ADMIN).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json(createBody(brand.getId(), 1_000, null))))
                .andExpect(status().isBadRequest());

            // assert
            assertThat(productJpaRepository.count()).isZero();
        }

        @DisplayName("PRD-01 price나 brandId가 빠지면 400이고, 저장되지 않는다.")
        @Test
        void rejectsMissingPriceOrBrand() throws Exception {
            // arrange
            BrandModel brand = brand();

            // act
            mockMvc.perform(post(ENDPOINT).with(ADMIN).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json(createBody(brand.getId(), null, 5))))
                .andExpect(status().isBadRequest());
            mockMvc.perform(post(ENDPOINT).with(ADMIN).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json(createBody(null, 1_000, 5))))
                .andExpect(status().isBadRequest());

            // assert
            assertThat(productJpaRepository.count()).isZero();
        }

        @DisplayName("PRD-01 가격·재고에 실수(1.9, 2.7)나 문자열 숫자(\"1000\")를 보내면 정수로 바꾸지 않고 400이며, 저장되지 않는다.")
        @Test
        void rejectsNonIntegerNumbers() throws Exception {
            // arrange
            BrandModel brand = brand();

            // act
            mockMvc.perform(post(ENDPOINT).with(ADMIN).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json(createBody(brand.getId(), 1.9, 5))))
                .andExpect(status().isBadRequest());
            mockMvc.perform(post(ENDPOINT).with(ADMIN).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json(createBody(brand.getId(), 1_000, 2.7))))
                .andExpect(status().isBadRequest());
            mockMvc.perform(post(ENDPOINT).with(ADMIN).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json(createBody(brand.getId(), "1000", 5))))
                .andExpect(status().isBadRequest());

            // assert
            assertThat(productJpaRepository.count()).isZero();
        }
    }

    @DisplayName("GET /api-admin/v1/products")
    @Nested
    class GetList {

        @DisplayName("brandId로 거르면 그 브랜드의 삭제되지 않은 상품만 돌려준다.")
        @Test
        void filtersByBrand() throws Exception {
            // arrange
            BrandModel nike = brand();
            BrandModel adidas = brandJpaRepository.save(new BrandModel("아디다스", null));
            ProductModel nikeProduct = product(nike, 5);
            product(adidas, 5);

            // act & assert
            mockMvc.perform(get(ENDPOINT).param("brandId", String.valueOf(nike.getId())).with(ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].id").value(nikeProduct.getId()));
        }
    }

    @DisplayName("PUT /api-admin/v1/products/{productId}")
    @Nested
    class Update {

        @DisplayName("PRD-03 이름·가격만 바꾸고, 요청에 다른 brandId가 있어도 브랜드는 그대로다.")
        @Test
        void updatesNameAndPriceButKeepsBrand() throws Exception {
            // arrange
            BrandModel brand = brand();
            BrandModel otherBrand = brandJpaRepository.save(new BrandModel("아디다스", null));
            ProductModel product = product(brand, 5);

            // act
            mockMvc.perform(put(ENDPOINT + "/" + product.getId()).with(ADMIN).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json(Map.of("name", "에어포스", "price", 2_000, "brandId", otherBrand.getId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.brandId").value(brand.getId()))
                .andExpect(result -> assertThat(jsonField(result, "updatedAt")).isNotEqualTo(jsonField(result, "createdAt")));

            // assert
            ProductModel reloaded = productJpaRepository.findById(product.getId()).orElseThrow();
            assertThat(reloaded.getName()).isEqualTo("에어포스");
            assertThat(reloaded.getPrice().amount()).isEqualTo(2_000);
            assertThat(reloaded.getBrandId()).isEqualTo(brand.getId());
        }

        @DisplayName("PRD-06 삭제된 상품은 수정할 수 없어 404다.")
        @Test
        void returnsNotFound_whenDeleted() throws Exception {
            // arrange
            ProductModel product = product(brand(), 5);
            product.delete();
            productJpaRepository.save(product);

            // act & assert
            mockMvc.perform(put(ENDPOINT + "/" + product.getId()).with(ADMIN).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json(Map.of("name", "에어포스", "price", 2_000))))
                .andExpect(status().isNotFound());
        }
    }

    @DisplayName("PUT /api-admin/v1/products/{productId}/stock")
    @Nested
    class ChangeStock {

        @DisplayName("PRD-04 0 이상의 최종 수량으로 설정한다.")
        @Test
        void setsFinalStock() throws Exception {
            // arrange
            ProductModel product = product(brand(), 5);

            // act
            mockMvc.perform(put(ENDPOINT + "/" + product.getId() + "/stock").with(ADMIN).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json(Map.of("stock", 0))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stock").value(0));

            // assert
            assertThat(productJpaRepository.findById(product.getId()).orElseThrow().getStock()).isZero();
        }

        @DisplayName("PRD-04 음수 재고는 400이고, 저장된 재고는 그대로다.")
        @Test
        void rejectsNegativeStock_andKeepsStoredStock() throws Exception {
            // arrange
            ProductModel product = product(brand(), 5);

            // act
            mockMvc.perform(put(ENDPOINT + "/" + product.getId() + "/stock").with(ADMIN).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json(Map.of("stock", -1))))
                .andExpect(status().isBadRequest());

            // assert
            assertThat(productJpaRepository.findById(product.getId()).orElseThrow().getStock()).isEqualTo(5);
        }

        @DisplayName("PRD-06 삭제된 상품의 재고는 바꿀 수 없어 404이고, 저장된 재고는 그대로다.")
        @Test
        void returnsNotFound_whenDeleted() throws Exception {
            // arrange
            ProductModel product = product(brand(), 5);
            product.delete();
            productJpaRepository.save(product);

            // act
            mockMvc.perform(put(ENDPOINT + "/" + product.getId() + "/stock").with(ADMIN).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json(Map.of("stock", 0))))
                .andExpect(status().isNotFound());

            // assert
            assertThat(productJpaRepository.findById(product.getId()).orElseThrow().getStock()).isEqualTo(5);
        }
    }

    @DisplayName("DELETE /api-admin/v1/products/{productId}")
    @Nested
    class Delete {

        @DisplayName("PRD-06 삭제하면 관리자 상세·목록에서 빠지고, 행은 논리 삭제로 남는다.")
        @Test
        void softDeletesProduct() throws Exception {
            // arrange
            ProductModel product = product(brand(), 5);

            // act
            mockMvc.perform(delete(ENDPOINT + "/" + product.getId()).with(ADMIN).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.result").value("SUCCESS"))
                .andExpect(jsonPath("$.data").doesNotExist());

            // assert
            mockMvc.perform(get(ENDPOINT + "/" + product.getId()).with(ADMIN))
                .andExpect(status().isNotFound());
            mockMvc.perform(get(ENDPOINT).with(ADMIN))
                .andExpect(jsonPath("$.data.totalElements").value(0));
            assertThat(productJpaRepository.findById(product.getId()).orElseThrow().getDeletedAt()).isNotNull();
        }

        @DisplayName("ROLE_USER 요청은 403이고, 상품은 삭제되지 않는다.")
        @Test
        void forbidsNonAdmin() throws Exception {
            // arrange
            ProductModel product = product(brand(), 5);

            // act
            mockMvc.perform(delete(ENDPOINT + "/" + product.getId()).with(user("customer").roles("USER")).with(csrf()))
                .andExpect(status().isForbidden());

            // assert
            assertThat(productJpaRepository.findById(product.getId()).orElseThrow().getDeletedAt()).isNull();
        }
    }

    @DisplayName("관리자 변경이 고객 조회에 반영될 때, ")
    @Nested
    class ReflectedToCustomer {

        @DisplayName("PRD-03·PRD-06 관리자가 가격을 바꾸면 고객 상품 상세에 바뀐 가격이 보이고, 관리자가 삭제하면 고객 상세는 404다.")
        @Test
        void customerSeesAdminChanges() throws Exception {
            // arrange
            ProductModel product = product(brand(), 5);

            // act & assert: 관리자 가격 수정 → 고객 상세
            mockMvc.perform(put(ENDPOINT + "/" + product.getId()).with(ADMIN).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json(Map.of("name", "에어맥스", "price", 120_000))))
                .andExpect(status().isOk());
            mockMvc.perform(get("/api/v1/products/" + product.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.price").value(120_000))
                .andExpect(jsonPath("$.data.stock").doesNotExist());

            // act & assert: 관리자 삭제 → 고객 상세
            mockMvc.perform(delete(ENDPOINT + "/" + product.getId()).with(ADMIN).with(csrf()))
                .andExpect(status().isOk());
            mockMvc.perform(get("/api/v1/products/" + product.getId()))
                .andExpect(status().isNotFound());
        }
    }
}
